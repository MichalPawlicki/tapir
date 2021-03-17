package sttp.tapir.client.http4s

import sttp.tapir.client.tests.ClientMultipartTests

class Http4sClientMultipartTests extends Http4sClientTests[Any] with ClientMultipartTests {
  multipartTests()
}
