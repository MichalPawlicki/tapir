package sttp.tapir.internal

import sttp.tapir._
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.annotations
import sttp.tapir.internal.{CaseClass, CaseClassField}
import sttp.tapir.typelevel.ParamConcat
import sttp.model.{QueryParams, Header, StatusCode}
import sttp.model.headers.{Cookie, CookieWithMeta, CookieValueWithMeta}

import scala.collection.mutable
import scala.quoted.*
import scala.deriving.Mirror

class AnnotationsMacros[T <: Product: Type](using q: Quotes) {
  import quotes.reflect.*

  private val caseClass = new CaseClass[q.type, T](using summon[Type[T]], q)

  def deriveEndpointInputImpl: Expr[EndpointInput[T]] = {
    // the path inputs must be defined in the order as they appear in the argument to @endpointInput
    val pathSegments = caseClass.extractOptArgFromAnnotation(endpointInputAnnotationSymbol).flatten
      .map { case path =>
        val pathWithoutLeadingSlash = if (path.startsWith("/")) path.drop(1) else path
        val result = if (pathWithoutLeadingSlash.endsWith("/")) pathWithoutLeadingSlash.dropRight(1) else pathWithoutLeadingSlash
        if (result.length == 0) Nil else result.split("/").toList
      }
      .getOrElse(Nil)

    val fieldsWithIndex = caseClass.fields.zipWithIndex
    val inputIdxToFieldIdx = mutable.Map.empty[Int, Int]

    val pathInputs = pathSegments.map { segment =>
      if (segment.startsWith("{") && segment.endsWith("}")) {
        val fieldName = segment.drop(1).dropRight(1)
        fieldsWithIndex.find { case (f, _) => f.name == fieldName && f.annotated(pathAnnotationSymbol) } match {
          case Some((field, idx)) =>
            field.tpe.asType match
              case '[f] =>
                inputIdxToFieldIdx += (inputIdxToFieldIdx.size -> idx)
                wrapInput(field, makePathInput[f](field))
          case None =>
            report.throwError(s"${caseClass.name}.${fieldName} must have the @path annotation, as it is referenced from @endpointInput.")
        }
      } else {
        makeFixedPath(segment)
      }
    }

    val (pathFields, nonPathFields) = fieldsWithIndex.partition((f, _) => f.annotated(pathAnnotationSymbol))

    if (inputIdxToFieldIdx.size != pathFields.size) {
      report.throwError(s"Not all fields of ${caseClass.name} annotated with @path are captured in the path in @endpointInput.")
    }

    val nonPathInputs = nonPathFields.map { case (field, fieldIdx) =>
      field.tpe.asType match
        case '[f] =>
          val input: Expr[EndpointInput.Single[f]] = field.extractOptArgFromAnnotation(queryAnnotationSymbol).map(makeQueryInput[f](field))
            .orElse(field.extractOptArgFromAnnotation(headerAnnotationSymbol).map(makeHeaderIO[f](field)))
            .orElse(field.extractOptArgFromAnnotation(cookieAnnotationSymbol).map(makeCookieInput[f](field)))
            .orElse(field.annotation(bodyAnnotationSymbol).map(makeBodyIO[f](field)))
            .orElse(if (field.annotated(paramsAnnotationSymbol)) Some(makeQueryParamsInput[f](field)) else None)
            .orElse(if (field.annotated(headersAnnotationSymbol)) Some(makeHeadersIO[f](field)) else None)
            .orElse(if (field.annotated(cookiesAnnotationSymbol)) Some(makeCookiesIO[f](field)) else None)
            .orElse(field.annotation(bearerAnnotationSymbol).map(makeBearerAuthInput[f](field, field.annotation(securitySchemeNameAnnotationSymbol), _)))
            .orElse(field.annotation(basicAnnotationSymbol).map(makeBasicAuthInput[f](field, field.annotation(securitySchemeNameAnnotationSymbol), _)))
            .getOrElse {
              report.throwError(s"All fields of ${caseClass.name} must be annotated with one of the annotations from sttp.tapir.annotations. No annotations for field: ${field.name}.")
            }
          inputIdxToFieldIdx += (inputIdxToFieldIdx.size -> fieldIdx)
          wrapInput(field, input)
    }

    val result = (pathInputs ::: nonPathInputs).map(_.asTerm).reduceLeft { (left, right) =>
      (left.tpe.asType, right.tpe.asType) match
        case ('[EndpointInput[l]], '[EndpointInput[r]]) =>
          // we need to summon explicitly to get the instance for the "real" l, r types
          val concat = Expr.summon[ParamConcat[l, r]].get
            concat.asTerm.tpe.asType match {
            case '[ParamConcat.Aux[`l`, `r`, lr]] =>
              '{${left.asExprOf[EndpointInput[l]]}.and(${right.asExprOf[EndpointInput[r]]})(using $concat.asInstanceOf[ParamConcat.Aux[l, r, lr]])}.asTerm
          }
    }

    result.tpe.asType match {
      case '[EndpointInput[r]] =>
        '{${result.asExprOf[EndpointInput[r]]}.map[T](${mapToTargetFunc[r](inputIdxToFieldIdx)})(${mapFromTargetFunc[r](inputIdxToFieldIdx)})}
    }
  }

  def deriveEndpointOutputImpl: Expr[EndpointOutput[T]] = {
    val fieldsWithIndex = caseClass.fields.zipWithIndex

    val outputs = fieldsWithIndex.map { case (field, fieldIdx) =>
      field.tpe.asType match
        case '[f] =>
          val output: Expr[EndpointOutput.Single[f]] = field.extractOptArgFromAnnotation(headerAnnotationSymbol).map(makeHeaderIO[f](field))
            .orElse(field.extractOptArgFromAnnotation(setCookieAnnotationSymbol).map(makeSetCookieOutput[f](field)))
            .orElse(field.annotation(bodyAnnotationSymbol).map(makeBodyIO[f](field)))
            .orElse(if (field.annotated(statusCodeAnnotationSymbol)) Some(makeStatusCodeOutput[f](field)) else None)
            .orElse(if (field.annotated(headersAnnotationSymbol)) Some(makeHeadersIO[f](field)) else None)
            .orElse(if (field.annotated(cookiesAnnotationSymbol)) Some(makeCookiesIO[f](field)) else None)
            .orElse(if (field.annotated(setCookiesAnnotationSymbol)) Some(makeSetCookiesOutput[f](field)) else None)
            .getOrElse {
              report.throwError(s"All fields of ${caseClass.name} must be annotated with one of the annotations from sttp.tapir.annotations. No annotations for field: ${field.name}.")
            }
          '{${addSchemaMetadata[f](field, output)}.asInstanceOf[EndpointOutput.Single[f]]}
    }

    val result = outputs.map(_.asTerm).reduceLeft { (left, right) =>
      (left.tpe.asType, right.tpe.asType) match
        case ('[EndpointOutput[l]], '[EndpointOutput[r]]) =>
          // we need to summon explicitly to get the instance for the "real" l, r types
          val concat = Expr.summon[ParamConcat[l, r]].get
            concat.asTerm.tpe.asType match {
            case '[ParamConcat.Aux[`l`, `r`, lr]] =>
              '{${left.asExprOf[EndpointOutput[l]]}.and(${right.asExprOf[EndpointOutput[r]]})(using $concat.asInstanceOf[ParamConcat.Aux[l, r, lr]])}.asTerm
          }
    }

    result.tpe.asType match {
      case '[EndpointOutput[r]] =>
        val inputIdxToFieldIdx = mutable.Map((0 until outputs.size).map(i => (i, i)): _*)
        '{${result.asExprOf[EndpointOutput[r]]}.map[T](${mapToTargetFunc[r](inputIdxToFieldIdx)})(${mapFromTargetFunc[r](inputIdxToFieldIdx)})}
    }
  }

  // annotation symbols
  private val endpointInputAnnotationSymbol = TypeTree.of[annotations.endpointInput].tpe.typeSymbol
  private val pathAnnotationSymbol = TypeTree.of[annotations.path].tpe.typeSymbol
  private val queryAnnotationSymbol = TypeTree.of[annotations.query].tpe.typeSymbol
  private val headerAnnotationSymbol = TypeTree.of[annotations.header].tpe.typeSymbol
  private val headersAnnotationSymbol = TypeTree.of[annotations.headers].tpe.typeSymbol
  private val cookieAnnotationSymbol = TypeTree.of[annotations.cookie].tpe.typeSymbol
  private val cookiesAnnotationSymbol = TypeTree.of[annotations.cookies].tpe.typeSymbol
  private val setCookieAnnotationSymbol = TypeTree.of[annotations.setCookie].tpe.typeSymbol
  private val setCookiesAnnotationSymbol = TypeTree.of[annotations.setCookies].tpe.typeSymbol
  private val paramsAnnotationSymbol = TypeTree.of[annotations.params].tpe.typeSymbol
  private val bearerAnnotationSymbol = TypeTree.of[annotations.bearer].tpe.typeSymbol
  private val basicAnnotationSymbol = TypeTree.of[annotations.basic].tpe.typeSymbol
  private val apikeyAnnotationSymbol = TypeTree.of[annotations.apikey].tpe.typeSymbol
  private val securitySchemeNameAnnotationSymbol = TypeTree.of[annotations.securitySchemeName].tpe.typeSymbol
  private val bodyAnnotationSymbol = TypeTree.of[annotations.body].tpe.typeSymbol
  private val statusCodeAnnotationSymbol = TypeTree.of[annotations.statusCode].tpe.typeSymbol

  // schema symbols
  private val descriptionAnnotationSymbol = TypeTree.of[description].tpe.typeSymbol
  private val deprecatedAnnotationSymbol = TypeTree.of[deprecated].tpe.typeSymbol

  // util
  private def summonCodec[L: Type, H: Type, CF <: CodecFormat: Type](field: CaseClassField[q.type, T]): Expr[Codec[L, H, CF]] = Expr.summon[Codec[L, H, CF]].getOrElse {
    report.throwError(s"Cannot summon codec from: ${Type.show[L]}, to: ${Type.show[H]}, formatted as: ${Type.show[CF]}, for field: ${field.name}.")
  }

  // inputs & outputs
  private def makeQueryInput[f: Type](field: CaseClassField[q.type, T])(altName: Option[String]): Expr[EndpointInput.Basic[f]] = {
    val name = Expr(altName.getOrElse(field.name))
    '{query[f]($name)(using ${summonCodec[List[String], f, TextPlain](field)})}
  }

  private def makeHeaderIO[f: Type](field: CaseClassField[q.type, T])(altName: Option[String]): Expr[EndpointIO.Basic[f]] = {
    val name = Expr(altName.getOrElse(field.name))
    '{header[f]($name)(using ${summonCodec[List[String], f, TextPlain](field)})}
  }

  private def makeCookieInput[f: Type](field: CaseClassField[q.type, T])(altName: Option[String]): Expr[EndpointInput.Basic[f]] = {
    val name = Expr(altName.getOrElse(field.name))
    '{cookie[f]($name)(using ${summonCodec[Option[String], f, TextPlain](field)})}
  }

  private def makeBodyIO[f: Type](field: CaseClassField[q.type, T])(ann: Term): Expr[EndpointIO.Basic[f]] = {
    val annExp = ann.asExprOf[annotations.body[_, _]]

    ann.tpe.asType match {
      case '[annotations.body[bt, cf]] =>
        '{EndpointIO.Body[bt, f](
          $annExp.bodyType.asInstanceOf[RawBodyType[bt]],
          ${summonCodec[bt, f, cf](field)}.asInstanceOf[Codec[bt, f, CodecFormat]],
          EndpointIO.Info.empty)}
        }
  }

  private def makeQueryParamsInput[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointInput.Basic[f]] = {
    summon[Type[f]] match {
      case '[QueryParams] => '{queryParams.asInstanceOf[EndpointInput.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, QueryParams]("@params", field))
    }
  }

  private def makeHeadersIO[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointIO.Basic[f]] =
    summon[Type[f]] match {
      case '[List[Header]] => '{headers.asInstanceOf[EndpointIO.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, List[Header]]("@headers", field))
    }

  private def makeCookiesIO[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointIO.Basic[f]] =
    summon[Type[f]] match {
      case '[List[Cookie]] => '{cookies.asInstanceOf[EndpointIO.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, List[Cookie]]("@cookies", field))
    }

  private def makePathInput[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointInput.Basic[f]] = {
    val name = Expr(field.name)
    '{path[f]($name)(using ${summonCodec[String, f, TextPlain](field)})}
  }

  private def makeFixedPath(segment: String): Expr[EndpointInput.Basic[Unit]] = '{stringToPath(${Expr(segment)})}

  private def makeStatusCodeOutput[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointOutput.Basic[f]] = {
    summon[Type[f]] match {
      case '[StatusCode] => '{statusCode.asInstanceOf[EndpointOutput.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, StatusCode]("@statusCode", field))
    }
  }

  private def makeSetCookieOutput[f: Type](field: CaseClassField[q.type, T])(altName: Option[String]): Expr[EndpointOutput.Basic[f]] = {
    val name = Expr(altName.getOrElse(field.name))
    summon[Type[f]] match {
      case '[CookieValueWithMeta] => '{setCookie($name).asInstanceOf[EndpointOutput.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, CookieValueWithMeta]("@setCookie", field))
    }
  }

  private def makeSetCookiesOutput[f: Type](field: CaseClassField[q.type, T]): Expr[EndpointOutput.Basic[f]] = {
    summon[Type[f]] match {
      case '[List[CookieWithMeta]] => '{setCookies.asInstanceOf[EndpointOutput.Basic[f]]}
      case _ => report.throwError(annotationErrorMsg[f, List[CookieWithMeta]]("@setCookies", field))
    }
  }

  private def annotationErrorMsg[f: Type, e: Type](annName: String, field: CaseClassField[q.type, T]) =
    s"Annotation $annName on ${field.name} can be applied only to a field with type ${Type.show[e]}, but got: ${Type.show[f]}"

  // auth inputs
  private def makeBearerAuthInput[f: Type](field: CaseClassField[q.type, T], schemeName: Option[Term], auth: Term): Expr[EndpointInput.Single[f]] =
    setSecuritySchemeName(
      '{TapirAuth.bearer(${auth.asExprOf[annotations.bearer]}.challenge)(${summonCodec[List[String], f, CodecFormat.TextPlain](field)})},
      schemeName)

  private def makeBasicAuthInput[f: Type](field: CaseClassField[q.type, T], schemeName: Option[Term], auth: Term): Expr[EndpointInput.Single[f]] =
    setSecuritySchemeName(
      '{TapirAuth.basic(${auth.asExprOf[annotations.basic]}.challenge)(${summonCodec[List[String], f, CodecFormat.TextPlain](field)})},
      schemeName)

  // schema & auth wrappers
  private def wrapInput[f: Type](field: CaseClassField[q.type, T], input: Expr[EndpointInput.Single[f]]): Expr[EndpointInput.Single[f]] = {
    val input2 = '{${addSchemaMetadata[f](field, input)}.asInstanceOf[EndpointInput.Single[f]]}
    wrapWithApiKey(input2, field.annotation(apikeyAnnotationSymbol), field.annotation(securitySchemeNameAnnotationSymbol))
  }

  private def wrapWithApiKey[f: Type](input: Expr[EndpointInput.Single[f]], apikey: Option[Term], schemeName: Option[Term]): Expr[EndpointInput.Single[f]] =
    apikey
      .map(ak => setSecuritySchemeName('{EndpointInput.Auth.ApiKey($input, ${ak.asExprOf[annotations.apikey]}.challenge, None)}, schemeName))
      .getOrElse(input)

  private def setSecuritySchemeName[f: Type](auth: Expr[EndpointInput.Auth[f]], schemeName: Option[Term]): Expr[EndpointInput.Single[f]] =
    schemeName
      .map(s => '{$auth.securitySchemeName(${s.asExprOf[annotations.securitySchemeName]}.name)})
      .getOrElse(auth)

  private def addSchemaMetadata[f: Type](field: CaseClassField[q.type, T], transput: Expr[EndpointTransput[f]]): Expr[EndpointTransput[f]] = {
    // TODO: add other metadata (also in Scala2)
    val transput2 = field.extractArgFromAnnotation(descriptionAnnotationSymbol)
      .map(d => addMetadataToBasic(field, transput, '{ i => i.description(${Expr(d)})}))
      .getOrElse(transput)

    if (field.annotated(deprecatedAnnotationSymbol)) then addMetadataToBasic(field, transput2, '{ i => i.deprecated()})
    else transput2
  }

  private def addMetadataToBasic[f: Type](field: CaseClassField[q.type, T],
                                          transput: Expr[EndpointTransput[f]],
                                          f: Expr[EndpointTransput.Basic[f] => Any]): Expr[EndpointTransput[f]] = {
    transput.asTerm.tpe.asType match {
      // TODO: also handle Auth inputs, by adding the description to the nested input
      case '[EndpointTransput.Basic[`f`]] => '{$f($transput.asInstanceOf[EndpointTransput.Basic[f]]).asInstanceOf[EndpointTransput.Basic[f]]}
      case t => report.throwError(s"Schema metadata can only be added to basic inputs/outputs, but got: ${Type.show(using t)}, on field: ${field.name}")
    }
  }

  // mapping functions
  // A - single arg or tuple, T - target type
  private def mapToTargetFunc[A: Type](inputIdxToFieldIdx: mutable.Map[Int, Int]): Expr[A => T] = {
    if (inputIdxToFieldIdx.size > 1) {
      val fieldIdxToInputIdx = inputIdxToFieldIdx.map(_.swap)
      def ctorArgs(tupleExpr: Expr[A]) = (0 until fieldIdxToInputIdx.size).map { idx =>
        Select.unique(tupleExpr.asTerm, s"_${fieldIdxToInputIdx(idx) + 1}").asExprOf[Any]
      }

      '{(a: A) => ${caseClass.instanceFromValues(ctorArgs('a))}}
    } else {
      '{(a: A) => ${caseClass.instanceFromValues('{List(a)})}}
    }
  }

  private def mapFromTargetFunc[A: Type](inputIdxToFieldIdx: mutable.Map[Int, Int]): Expr[T => A] = {
    def tupleArgs(tExpr: Expr[T]): Seq[Expr[Any]] = (0 until inputIdxToFieldIdx.size).map { idx =>
      val field = caseClass.fields(inputIdxToFieldIdx(idx))
      Select.unique(tExpr.asTerm, field.name).asExprOf[Any]
    }

    '{(t: T) => ${Expr.ofTupleFromSeq(tupleArgs('t))}.asInstanceOf[A]}
  }
}

