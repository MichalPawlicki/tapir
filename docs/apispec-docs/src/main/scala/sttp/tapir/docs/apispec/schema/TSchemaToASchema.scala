package sttp.tapir.docs.apispec.schema

import sttp.tapir.SchemaType.SObjectInfo
import sttp.tapir.apispec.{ReferenceOr, Schema => ASchema, _}
import sttp.tapir.docs.apispec.exampleValue
import sttp.tapir.internal.{IterableToListMap, _}
import sttp.tapir.{Validator, Schema => TSchema, SchemaType => TSchemaType}

/** Converts a tapir schema to an OpenAPI/AsyncAPI schema, using the given map to resolve references. */
private[schema] class TSchemaToASchema(
    objectToSchemaReference: ObjectToSchemaReference,
    referenceEnums: SObjectInfo => Boolean
) {
  def apply[T](schema: TSchema[T]): ReferenceOr[ASchema] = {
    val result = schema.schemaType match {
      case TSchemaType.SInteger() => Right(ASchema(SchemaType.Integer))
      case TSchemaType.SNumber()  => Right(ASchema(SchemaType.Number))
      case TSchemaType.SBoolean() => Right(ASchema(SchemaType.Boolean))
      case TSchemaType.SString()  => Right(ASchema(SchemaType.String))
      case p @ TSchemaType.SProduct(_, fields) =>
        Right(
          ASchema(SchemaType.Object).copy(
            required = p.required.map(_.encodedName),
            properties = fields.map { f =>
              f.schema match {
                case TSchema(s: TSchemaType.SObject[_], _, _, _, _, _, _, _) =>
                  f.name.encodedName -> Left(objectToSchemaReference.map(s.info))
                case schema =>
                  schemaIsReferencedEnum(schema) match {
                    case Some(enumInfo) => f.name.encodedName -> Left(objectToSchemaReference.map(enumInfo))
                    case None           => f.name.encodedName -> apply(schema)
                  }
              }
            }.toListMap
          )
        )
      case TSchemaType.SArray(TSchema(el: TSchemaType.SObject[_], _, _, _, _, _, _, _)) =>
        Right(ASchema(SchemaType.Array).copy(items = Some(Left(objectToSchemaReference.map(el.info)))))
      case TSchemaType.SArray(el) =>
        val items = schemaIsReferencedEnum(el) match {
          case Some(enumInfo) => Some(Left(objectToSchemaReference.map(enumInfo)))
          case None           => Some(apply(el))
        }
        Right(ASchema(SchemaType.Array).copy(items = items))
      case TSchemaType.SOption(TSchema(el: TSchemaType.SObject[_], _, _, _, _, _, _, _)) => Left(objectToSchemaReference.map(el.info))
      case TSchemaType.SOption(el)                                                       => apply(el)
      case TSchemaType.SBinary()                                                         => Right(ASchema(SchemaType.String).copy(format = SchemaFormat.Binary))
      case TSchemaType.SDate()                                                           => Right(ASchema(SchemaType.String).copy(format = SchemaFormat.Date))
      case TSchemaType.SDateTime()                                                       => Right(ASchema(SchemaType.String).copy(format = SchemaFormat.DateTime))
      case TSchemaType.SRef(fullName)                                                    => Left(objectToSchemaReference.map(fullName))
      case TSchemaType.SCoproduct(_, schemas, d) =>
        Right(
          ASchema.apply(
            schemas.values.toList
              .collect { case TSchema(s: TSchemaType.SProduct[_], _, _, _, _, _, _, _) =>
                Left(objectToSchemaReference.map(s.info))
              }
              .sortBy { case Left(Reference(ref)) =>
                ref
              },
            d.map(tDiscriminatorToADiscriminator)
          )
        )
      case TSchemaType.SOpenProduct(_, valueSchema) =>
        Right(
          ASchema(SchemaType.Object).copy(
            required = List.empty,
            additionalProperties = Some(valueSchema.schemaType match {
              case so: TSchemaType.SObject[_] => Left(objectToSchemaReference.map(so.info))
              case _                          => apply(valueSchema)
            })
          )
        )
    }

    val primitiveValidators = schema.validator.asPrimitiveValidators
    val wholeNumbers = schema.schemaType match {
      case TSchemaType.SInteger() => true
      case _                      => false
    }

    result
      .map(addMetadata(_, schema))
      .map(addConstraints(_, primitiveValidators, wholeNumbers))
  }

  private def schemaIsReferencedEnum(schema: TSchema[_]): Option[SObjectInfo] = {
    val validatorToCheck = schema match {
      case TSchema(TSchemaType.SOption(TSchema(_, _, _, _, _, _, _, v)), _, _, _, _, _, _, _) => v
      case TSchema(_, _, _, _, _, _, _, v)                                                    => v
    }

    validatorToCheck.traversePrimitives { case Validator.Enumeration(_, _, Some(info)) => Vector(info) } match {
      case info +: _ if referenceEnums(info) => Some(info)
      case _                                 => None
    }
  }

  private def addMetadata(oschema: ASchema, tschema: TSchema[_]): ASchema = {
    oschema.copy(
      description = tschema.description.orElse(oschema.description),
      default = tschema.default.flatMap { case (_, raw) => raw.flatMap(r => exampleValue(tschema, r)) }.orElse(oschema.default),
      example = tschema.encodedExample.flatMap(exampleValue(tschema, _)).orElse(oschema.example),
      format = tschema.format.orElse(oschema.format),
      deprecated = (if (tschema.deprecated) Some(true) else None).orElse(oschema.deprecated)
    )
  }

  private def addConstraints(
      oschema: ASchema,
      vs: Seq[Validator.Primitive[_]],
      wholeNumbers: Boolean
  ): ASchema = vs.foldLeft(oschema)(addConstraints(_, _, wholeNumbers))

  private def addConstraints(oschema: ASchema, v: Validator.Primitive[_], wholeNumbers: Boolean): ASchema = {
    v match {
      case m @ Validator.Min(v, exclusive) =>
        oschema.copy(
          minimum = Some(toBigDecimal(v, m.valueIsNumeric, wholeNumbers)),
          exclusiveMinimum = Option(exclusive).filter(identity)
        )
      case m @ Validator.Max(v, exclusive) =>
        oschema.copy(
          maximum = Some(toBigDecimal(v, m.valueIsNumeric, wholeNumbers)),
          exclusiveMaximum = Option(exclusive).filter(identity)
        )
      case Validator.Pattern(value)          => oschema.copy(pattern = Some(value))
      case Validator.MinLength(value)        => oschema.copy(minLength = Some(value))
      case Validator.MaxLength(value)        => oschema.copy(maxLength = Some(value))
      case Validator.MinSize(value)          => oschema.copy(minItems = Some(value))
      case Validator.MaxSize(value)          => oschema.copy(maxItems = Some(value))
      case Validator.Enumeration(_, None, _) => oschema
      case Validator.Enumeration(v, Some(encode), _) =>
        val values = v.flatMap(x => encode(x).map(ExampleSingleValue))
        oschema.copy(`enum` = if (values.nonEmpty) Some(values) else None)
    }
  }

  private def toBigDecimal[N](v: N, vIsNumeric: Numeric[N], wholeNumber: Boolean): BigDecimal = {
    if (wholeNumber) BigDecimal(vIsNumeric.toLong(v)) else BigDecimal(vIsNumeric.toDouble(v))
  }

  private def tDiscriminatorToADiscriminator(discriminator: TSchemaType.SDiscriminator): Discriminator = {
    val schemas = Some(
      discriminator.mapping.map { case (k, TSchemaType.SRef(fullName)) =>
        k -> objectToSchemaReference.map(fullName).$ref
      }.toListMap
    )
    Discriminator(discriminator.name.encodedName, schemas)
  }
}
