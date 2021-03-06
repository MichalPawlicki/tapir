package sttp.tapir.internal

import sttp.tapir.SchemaType._
import sttp.tapir.generic.Configuration
import sttp.tapir.{FieldName, Schema, SchemaType, default, deprecated, description, encodedExample, encodedName, format, validate}
import SchemaMagnoliaDerivation.deriveCache
import sttp.tapir.internal.IterableToListMap

import scala.collection.mutable
import magnolia._
import scala.deriving.Mirror

trait SchemaMagnoliaDerivation {

  inline def derived[T](using genericDerivationConfig: Configuration, m: Mirror.Of[T]): Schema[T] = {
    val derivation = new Derivation[Schema] {
        type Typeclass[T] = Schema[T]

        override def join[T](ctx: CaseClass[Schema, T]): Schema[T] = {
          withCache(ctx.typeInfo, ctx.annotations) {
            val result =
              if (ctx.isValueClass) {
                Schema[T](schemaType = ctx.params.head.typeclass.schemaType.asInstanceOf[SchemaType[T]])
              } else {
                Schema[T](schemaType = productSchemaType(ctx))
              }
            enrichSchema(result, ctx.annotations)
          }
        }

        private def productSchemaType[T](ctx: CaseClass[Schema, T]): SProduct[T] =
          SProduct(
            typeNameToObjectInfo(ctx.typeInfo, ctx.annotations),
            ctx.params.map { p =>
              val pSchema = enrichSchema(p.typeclass, p.annotations)
              val encodedName = getEncodedName(p.annotations).getOrElse(genericDerivationConfig.toEncodedName(p.label))

              SProductField[T, p.PType](FieldName(p.label, encodedName), pSchema, t => Some(p.deref(t)))
            }.toList
          )

        private def typeNameToObjectInfo(typeName: TypeInfo, annotations: Seq[Any]): SchemaType.SObjectInfo = {
          def allTypeArguments(tn: TypeInfo): Seq[TypeInfo] = tn.typeParams.toList.flatMap(tn2 => tn2 +: allTypeArguments(tn2))

          annotations.collectFirst { case ann: encodedName => ann.name } match {
            case Some(altName) =>
              SObjectInfo(altName, Nil)
            case None =>
              SObjectInfo(typeName.full, allTypeArguments(typeName).map(_.short).toList)
          }
        }

        private def getEncodedName(annotations: Seq[Any]): Option[String] =
          annotations.collectFirst { case ann: encodedName => ann.name }

        private def enrichSchema[X](schema: Schema[X], annotations: Seq[Any]): Schema[X] = {
          annotations.foldLeft(schema) {
            case (schema, ann: description)    => schema.description(ann.text)
            case (schema, ann: encodedExample) => schema.encodedExample(ann.example)
            case (schema, ann: default[X])     => schema.default(ann.default)
            case (schema, ann: validate[X])    => schema.validate(ann.v)
            case (schema, ann: format)         => schema.format(ann.format)
            case (schema, _: deprecated)       => schema.deprecated(true)
            case (schema, _)                   => schema
          }
        }

        override def split[T](ctx: SealedTrait[Schema, T]): Schema[T] = {
          withCache(ctx.typeInfo, ctx.annotations) {
            val baseCoproduct = SCoproduct(
              typeNameToObjectInfo(ctx.typeInfo, ctx.annotations),
              ctx.subtypes.toList.map(s => typeNameToObjectInfo(s.typeInfo, s.annotations) -> s.typeclass.asInstanceOf[Typeclass[T]]).toListMap,
              None
            )((t: T) => ctx.choose(t) { v => Some(typeNameToObjectInfo(v.typeInfo, v.annotations)) })
            val coproduct = genericDerivationConfig.discriminator match {
              case Some(d) => baseCoproduct.addDiscriminatorField(FieldName(d))
              case None    => baseCoproduct
            }

            Schema(schemaType = coproduct)
          }
        }

        /** To avoid recursive loops, we keep track of the fully qualified names of types for which derivation is in
          * progress using a mutable Set.
          */
        private def withCache[T](typeName: TypeInfo, annotations: Seq[Any])(f: => Schema[T]): Schema[T] = {
          val cacheKey = typeName.full
          var inProgress = deriveCache.get()
          val newCache = inProgress == null
          if (newCache) {
            inProgress = mutable.Set[String]()
            deriveCache.set(inProgress)
          }

          if (inProgress.contains(cacheKey)) {
            Schema[T](SRef(typeNameToObjectInfo(typeName, annotations)))
          } else {
            try {
              inProgress.add(cacheKey)
              val schema = f
              schema
            } finally {
              inProgress.remove(cacheKey)
              if (newCache) {
                deriveCache.remove()
              }
            }
          }
        }
    }

    derivation.derived[T]
  }
}

object SchemaMagnoliaDerivation {
  private[internal] val deriveCache: ThreadLocal[mutable.Set[String]] = new ThreadLocal()
}
