package sttp.tapir.client.http4s

import cats.Applicative
import cats.effect.{Blocker, ContextShift, Effect, Sync}
import cats.implicits._
import fs2.{Chunk, Collector}
import org.http4s
import org.http4s.{Request, Response}
import sttp.capabilities.Streams
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Codec.PlainCodec
import sttp.tapir.internal.{CombineParams, Params, ParamsAsAny, RichEndpointOutput, SplitParams}
import sttp.tapir.{
  Codec,
  CodecFormat,
  DecodeResult,
  Endpoint,
  EndpointIO,
  EndpointInput,
  EndpointOutput,
  Mapping,
  RawBodyType,
  StreamBodyIO
}

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.ByteBuffer
import scala.collection.Seq

private[http4s] class EndpointToHttp4sClient(blocker: Blocker, clientOptions: Http4sClientOptions) {

  def toHttp4sRequest[I, E, O, R, F[_]: Sync: ContextShift: Effect](
      e: Endpoint[I, E, O, R],
      baseUriStr: String
  ): I => F[(Request[F], Response[F] => F[DecodeResult[Either[E, O]]])] = { params =>
    val baseUri = http4s.Uri.unsafeFromString(baseUriStr)
    val baseRequest = http4s.Request[F](uri = baseUri)
    val request = setInputParams[I, F](e.input, ParamsAsAny(params), baseRequest)

    def responseParser(response: http4s.Response[F]): F[DecodeResult[Either[E, O]]] = {
      parseHttp4sResponse(e).apply(response)
    }

    request.tupleRight(responseParser)
  }

  def toHttp4sRequestUnsafe[I, E, O, R, F[_]: Sync: ContextShift: Effect](
      e: Endpoint[I, E, O, R],
      baseUri: String
  ): I => F[(Request[F], Response[F] => F[Either[E, O]])] = { params =>
    toHttp4sRequest[I, E, O, R, F](e, baseUri).apply(params).map { case (request, responseParser) =>
      def unsafeResponseParser(response: http4s.Response[F]): F[Either[E, O]] =
        responseParser(response).map {
          case DecodeResult.Value(v)    => v
          case DecodeResult.Error(_, e) => throw e
          case f                        => throw new IllegalArgumentException(s"Cannot decode: $f")
        }

      (request, unsafeResponseParser)
    }
  }

  @scala.annotation.tailrec
  private def setInputParams[I, F[_]: Sync: ContextShift: Effect](
      input: EndpointInput[I],
      params: Params,
      currentReq: http4s.Request[F]
  ): F[http4s.Request[F]] = {
    def value: I = params.asAny.asInstanceOf[I]
    //    val baseUri = http4s.Uri.unsafeFromString(baseUriStr)
    //    val baseReq = http4s.Request[F](uri = baseUri)
    input match {
      case EndpointInput.FixedMethod(m, _, _) =>
        currentReq.withMethod(http4s.Method.fromString(m.method).right.get).pure[F]
      case EndpointInput.FixedPath(p, _, _) =>
        currentReq.withUri(currentReq.uri.addPath(p)).pure[F]
      case EndpointInput.PathCapture(_, codec, _) =>
        val v = codec.asInstanceOf[PlainCodec[Any]].encode(value: Any)
        currentReq.withUri(currentReq.uri.addPath(v)).pure[F]
      case EndpointInput.PathsCapture(codec, _) =>
        val ps = codec.encode(value)
        val uri = ps.foldLeft(currentReq.uri)(_.addPath(_))
        currentReq.withUri(uri).pure[F]
      case EndpointInput.Query(name, codec, _) =>
        val encodedParams = codec.encode(value)
        currentReq.withUri(currentReq.uri.withQueryParam(name, encodedParams)).pure[F]
      case EndpointInput.Cookie(name, codec, _) =>
        codec.encode(value).foldLeft(currentReq)(_.addCookie(name, _)).pure[F]
      case EndpointInput.QueryParams(codec, _) =>
        val uri =
          codec.encode(value).toMultiSeq.foldLeft(currentReq.uri) { case (currentUri, (key, values)) =>
            currentUri.withQueryParam(key, values)
          }
        currentReq.withUri(uri).pure[F]
      case EndpointIO.Empty(_, _) =>
        currentReq.pure[F]
      case EndpointIO.Body(bodyType, codec, _) =>
        setBody(value, bodyType, codec, currentReq)
      case EndpointIO.StreamBodyWrapper(StreamBodyIO(streams, _, _, _)) =>
        setStreamingBody(streams)(value.asInstanceOf[streams.BinaryStream], currentReq)
      case EndpointIO.Header(name, codec, _) =>
        val headers = codec.encode(value).map(value => http4s.Header(name, value))
        currentReq.putHeaders(headers: _*).pure[F]
      case EndpointIO.Headers(codec, _) =>
        val headers = codec.encode(value).map(h => http4s.Header(h.name, h.value))
        currentReq.putHeaders(headers: _*).pure[F]
      case EndpointIO.FixedHeader(h, _, _) =>
        currentReq.putHeaders(http4s.Header(h.name, h.value)).pure[F]
      case EndpointInput.ExtractFromRequest(_, _) =>
        // ignoring
        currentReq.pure[F]
      case a: EndpointInput.Auth[_] =>
        setInputParams(a.input, params, currentReq)
      case EndpointInput.Pair(left, right, _, split) => handleInputPair(left, right, params, split, currentReq)
      case EndpointIO.Pair(left, right, _, split)    => handleInputPair(left, right, params, split, currentReq)
      case EndpointInput.MappedPair(wrapped, codec)  => handleMapped(wrapped, codec.asInstanceOf[Mapping[Any, Any]], params, currentReq)
      case EndpointIO.MappedPair(wrapped, codec)     => handleMapped(wrapped, codec.asInstanceOf[Mapping[Any, Any]], params, currentReq)
      case inp =>
        throw new IllegalArgumentException(s"Input not supported yet: $inp")
    }
  }

  private def setBody[R, T, CF <: CodecFormat, F[_]: Sync: ContextShift: Effect](
      value: T,
      bodyType: RawBodyType[R],
      codec: Codec[R, T, CF],
      req: http4s.Request[F]
  ): F[http4s.Request[F]] = {
    val encoded: R = codec.encode(value)
    // TODO can't we get rid of asInstanceOf ?
    val newReq: http4s.Request[F] = bodyType match {
      case RawBodyType.StringBody(charset) =>
        // TODO: what about charset?
        val entityEncoder = http4s.EntityEncoder.stringEncoder[F](http4s.Charset.fromNioCharset(charset))
        req.withEntity(encoded.asInstanceOf[String])(entityEncoder)
      case RawBodyType.ByteArrayBody =>
//        http4s.EntityEncoder.byteArrayEncoder.toEntity(encoded.asInstanceOf[Array[Byte]])
        req.withEntity(encoded.asInstanceOf[Array[Byte]])
      case RawBodyType.ByteBufferBody =>
        val entityEncoder = http4s.EntityEncoder.chunkEncoder[F].contramap(Chunk.byteBuffer)
        req.withEntity(encoded.asInstanceOf[ByteBuffer])(entityEncoder)
      case RawBodyType.InputStreamBody =>
//        throw new IllegalArgumentException("RawBodyType.InputStreamBody not supported yet")
        val entityEncoder = http4s.EntityEncoder.inputStreamEncoder[F, InputStream](blocker)
        req.withEntity(encoded.asInstanceOf[InputStream].pure[F])(entityEncoder)
      case RawBodyType.FileBody =>
        val entityEncoder = http4s.EntityEncoder.fileEncoder[F](blocker)
        req.withEntity(encoded.asInstanceOf[File])(entityEncoder)
      case m: RawBodyType.MultipartBody =>
        //        val parts: Seq[PlayPart] = (encoded: Seq[RawPart]).flatMap { p =>
        //          m.partType(p.name).map { partType =>
        //            // name, body, content type, content length, file name
        //            val playPart =
        //              partToPlayPart(p.asInstanceOf[Part[Any]], partType.asInstanceOf[RawBodyType[Any]], p.contentType, p.contentLength, p.fileName)
        //
        //            // headers; except content type set above
        //            p.headers
        //              .filterNot(_.is(HeaderNames.ContentType))
        //              .foreach { header =>
        //                playPart.addCustomHeader(header.name, header.value)
        //              }
        //
        //            playPart
        //          }
        //        }
        //
        // TODO we need a BodyWritable[Source[PlayPart, _]]
        // But it's not part of Play Standalone
        // See https://github.com/playframework/playframework/blob/master/transport/client/play-ws/src/main/scala/play/api/libs/ws/WSBodyWritables.scala
        // req.withBody(Source(parts.toList))

        throw new IllegalArgumentException("Multipart body aren't supported")
    }

    val contentType = http4s.headers.`Content-Type`.parse(codec.format.mediaType.toString()).right.get
    newReq.withContentType(contentType).pure[F]
  }

  private def setStreamingBody[S, F[_]: Applicative: Effect](
      streams: Streams[S]
  )(value: streams.BinaryStream, request: Request[F]): F[Request[F]] =
    streams match {
      case _: Fs2Streams[_] =>
        request.withEntity(value.asInstanceOf[Fs2Streams[F]#BinaryStream]).pure[F]
      case _ =>
        Effect[F].raiseError(new IllegalArgumentException("Only Fs2Streams streaming is supported"))
    }

  private def handleInputPair[I, F[_]: Sync: ContextShift: Effect](
      left: EndpointInput[_],
      right: EndpointInput[_],
      params: Params,
      split: SplitParams,
      currentReq: http4s.Request[F]
  ): F[Request[F]] = {
    val (leftParams, rightParams) = split(params)
    for {
      req2 <- setInputParams(left.asInstanceOf[EndpointInput[Any]], leftParams, currentReq)
      req3 <- setInputParams(right.asInstanceOf[EndpointInput[Any]], rightParams, req2)
    } yield req3
  }

  private def handleMapped[II, T, F[_]: Sync: ContextShift: Effect](
      tuple: EndpointInput[II],
      codec: Mapping[T, II],
      params: Params,
      req: http4s.Request[F]
  ): F[http4s.Request[F]] = {
    setInputParams(tuple.asInstanceOf[EndpointInput[Any]], ParamsAsAny(codec.encode(params.asAny.asInstanceOf[II])), req)
  }

  private def parseHttp4sResponse[I, E, O, R, F[_]: Sync: ContextShift](
      e: Endpoint[I, E, O, R]
  ): http4s.Response[F] => F[DecodeResult[Either[E, O]]] = { response =>
    val code = sttp.model.StatusCode(response.status.code)

    val parser = if (code.isSuccess) responseFromOutput[F](e.output) else responseFromOutput[F](e.errorOutput)
    val output = if (code.isSuccess) e.output else e.errorOutput

    // headers with cookies
    val headers: Map[String, List[String]] = response.headers.toList.groupBy(_.name.value).mapValues(_.map(_.value)).toMap

    parser(response).map { responseBody =>
      val params = getOutputParams(output, responseBody, headers, code, response.status.reason)

      params.map(_.asAny).map(p => if (code.isSuccess) Right(p.asInstanceOf[O]) else Left(p.asInstanceOf[E]))
    }
  }

  private def responseFromOutput[F[_]: Sync: ContextShift](out: EndpointOutput[_]): http4s.Response[F] => F[Any] = { response =>
    bodyIsStream(out) match {
      case Some(streams) =>
        streams match {
          case _: Fs2Streams[_] =>
            val body: Fs2Streams[F]#BinaryStream = response.body
            body.asInstanceOf[Any].pure[F]
          case _ => throw new IllegalArgumentException("Streams are not supported yet")
        }
      case None =>
        import cats.implicits._
        out.bodyType
          .map[F[Any]] {
            case RawBodyType.StringBody(_) =>
              response.body.chunks.through(fs2.text.utf8DecodeC).compile.to(Collector.string).map(_.asInstanceOf[Any])
            case RawBodyType.ByteArrayBody =>
              response.body.compile.toVector.map(_.toArray).map(_.asInstanceOf[Any])
            case RawBodyType.ByteBufferBody =>
              response.body.compile.toVector.map(_.toArray).map(java.nio.ByteBuffer.wrap).map(_.asInstanceOf[Any])
            case RawBodyType.InputStreamBody =>
              response.body.compile.toVector.map(_.toArray).map(new ByteArrayInputStream(_)).map(_.asInstanceOf[Any])
            case RawBodyType.FileBody =>
              val file = clientOptions.createFile()
              import cats.implicits._
              response.body.through(fs2.io.file.writeAll(file.toPath, blocker)).compile.drain.map(_ => file.asInstanceOf[Any])
//              throw new IllegalArgumentException("RawBodyType.FileBody is not supported yet")
//              Fil
//              val
            // TODO Consider using bodyAsSource to not load the whole content in memory
            //              val f = clientOptions.createFile()
            //              val outputStream = Files.newOutputStream(f.toPath)
            //              outputStream.write(response.body[Array[Byte]])
            //              outputStream.close()
            //              f
            case RawBodyType.MultipartBody(_, _) => throw new IllegalArgumentException("Multipart bodies aren't supported in responses")
          }
          .getOrElse[F[Any]](((): Any).pure[F])
    }
  }

  private def bodyIsStream[I](out: EndpointOutput[I]): Option[Streams[_]] = {
    out.traverseOutputs { case EndpointIO.StreamBodyWrapper(StreamBodyIO(streams, _, _, _)) =>
      Vector(streams)
    }.headOption
  }

  private def getOutputParams(
      output: EndpointOutput[_],
      body: => Any,
      headers: Map[String, Seq[String]],
      code: sttp.model.StatusCode,
      statusText: String
  ): DecodeResult[Params] = {
    output match {
      case s: EndpointOutput.Single[_] =>
        (s match {
          case EndpointIO.Body(_, codec, _)                               => codec.decode(body)
          case EndpointIO.StreamBodyWrapper(StreamBodyIO(_, codec, _, _)) => codec.decode(body)
          case EndpointOutput.WebSocketBodyWrapper(_) =>
            DecodeResult.Error("", new IllegalArgumentException("WebSocket aren't supported yet"))
          case EndpointIO.Header(name, codec, _) => codec.decode(headers(name).toList)
          case EndpointIO.Headers(codec, _) =>
            val h = headers.flatMap { case (k, v) => v.map(sttp.model.Header(k, _)) }.toList
            codec.decode(h)
          case EndpointOutput.StatusCode(_, codec, _)      => codec.decode(code)
          case EndpointOutput.FixedStatusCode(_, codec, _) => codec.decode(())
          case EndpointIO.FixedHeader(_, codec, _)         => codec.decode(())
          case EndpointIO.Empty(codec, _)                  => codec.decode(())
          case EndpointOutput.OneOf(mappings, codec) =>
            mappings
              .find(mapping => mapping.statusCode.isEmpty || mapping.statusCode.contains(code)) match {
              case Some(mapping) =>
                getOutputParams(mapping.output, body, headers, code, statusText).flatMap(p => codec.decode(p.asAny))
              case None =>
                DecodeResult.Error(
                  statusText,
                  new IllegalArgumentException(s"Cannot find mapping for status code ${code} in outputs $output")
                )
            }

          case EndpointIO.MappedPair(wrapped, codec) =>
            getOutputParams(wrapped, body, headers, code, statusText).flatMap(p => codec.decode(p.asAny))
          case EndpointOutput.MappedPair(wrapped, codec) =>
            getOutputParams(wrapped, body, headers, code, statusText).flatMap(p => codec.decode(p.asAny))

        }).map(ParamsAsAny)

      case EndpointOutput.Void()                        => DecodeResult.Error("", new IllegalArgumentException("Cannot convert a void output to a value!"))
      case EndpointOutput.Pair(left, right, combine, _) => handleOutputPair(left, right, combine, body, headers, code, statusText)
      case EndpointIO.Pair(left, right, combine, _)     => handleOutputPair(left, right, combine, body, headers, code, statusText)
    }
  }

  private def handleOutputPair(
      left: EndpointOutput[_],
      right: EndpointOutput[_],
      combine: CombineParams,
      body: => Any,
      headers: Map[String, Seq[String]],
      code: sttp.model.StatusCode,
      statusText: String
  ): DecodeResult[Params] = {
    val l = getOutputParams(left, body, headers, code, statusText)
    val r = getOutputParams(right, body, headers, code, statusText)
    l.flatMap(leftParams => r.map(rightParams => combine(leftParams, rightParams)))
  }

}
