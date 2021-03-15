package sttp.tapir.client.http4s

import cats.effect.{Blocker, ContextShift, IO, Timer}
import org.http4s.client.blaze.BlazeClientBuilder
import sttp.tapir.client.tests.ClientTests
import sttp.tapir.{DecodeResult, Endpoint}

import scala.concurrent.ExecutionContext.global

abstract class Http4sClientTests[R] extends ClientTests[R] {
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val blocker: Blocker = Blocker.liftExecutionContext(global)

  override def send[I, E, O, FN[_]](e: Endpoint[I, E, O, R], port: Port, args: I, scheme: String = "http"): IO[Either[E, O]] = {
    val (request, respParser) = Http4sClientInterpreter.toRequestUnsafe[I, E, O, R, IO](e, s"http://localhost:$port").apply(args)
    for {
      req <- request
      decodeResult <- BlazeClientBuilder[IO](global).resource.use { client =>
        client.run(req).use(respParser)
      }
    } yield decodeResult
  }

  override def safeSend[I, E, O, FN[_]](e: Endpoint[I, E, O, R], port: Port, args: I): IO[DecodeResult[Either[E, O]]] = {
    val (request, respParser) = Http4sClientInterpreter.toRequest[I, E, O, R, IO](e, s"http://localhost:$port").apply(args)
    for {
      req <- request
      decodeResult <- BlazeClientBuilder[IO](global).resource.use { client =>
        client.run(req).use(respParser)
      }
    } yield decodeResult
  }
}
