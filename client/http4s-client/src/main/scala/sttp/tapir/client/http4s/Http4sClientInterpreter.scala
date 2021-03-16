package sttp.tapir.client.http4s

import cats.FlatMap
import cats.effect.{Blocker, ContextShift, Effect, Sync}
import org.http4s.{Request, Response}
import sttp.tapir.{DecodeResult, Endpoint}

trait Http4sClientInterpreter {

  /** Interprets the endpoint as a client call, using the given `baseUri` as the starting point to create the target
    * uri. If `baseUri` is not provided, the request will be a relative one.
    *
    * TODO: update
    * Returns a function which, when applied to the endpoint's input parameters (given as a tuple), will encode them
    * to appropriate request parameters: path, query, headers and body. The result of the function is
    * an `org.http4s.Request[F]`, which can be sent using an http4s client, or run against `org.http4s.HttpRoutes[F]`.
    * The response will then contain the decoded error or
    * success values (note that this can be the body enriched with data from headers/status code).
    */
  def toRequest[I, E, O, R, F[_]: Sync: ContextShift: Effect: FlatMap](e: Endpoint[I, E, O, R], baseUri: String)(implicit
      blocker: Blocker,
      clientOptions: Http4sClientOptions
  ): I => F[(Request[F], Response[F] => F[DecodeResult[Either[E, O]]])] =
    new EndpointToHttp4sClient(blocker, clientOptions).toHttp4sRequest[I, E, O, R, F](e, baseUri)

  /** Interprets the endpoint as a client call, using the given `baseUri` as the starting point to create the target
    * uri. If `baseUri` is not provided, the request will be a relative one.
    *
    * TODO: update
    * Returns a function which, when applied to the endpoint's input parameters (given as a tuple), will encode them
    * to appropriate request parameters: path, query, headers and body. The result of the function is
    * an `org.http4s.Request[F]`, which can be sent using an http4s client, or run against `org.http4s.HttpRoutes[F]`.
    */
  def toRequestUnsafe[I, E, O, R, F[_]: Sync: ContextShift: Effect: FlatMap](e: Endpoint[I, E, O, R], baseUri: String)(implicit
      blocker: Blocker,
      clientOptions: Http4sClientOptions
  ): I => F[(Request[F], Response[F] => F[Either[E, O]])] =
    new EndpointToHttp4sClient(blocker, clientOptions).toHttp4sRequestUnsafe[I, E, O, R, F](e, baseUri)
}

object Http4sClientInterpreter extends Http4sClientInterpreter
