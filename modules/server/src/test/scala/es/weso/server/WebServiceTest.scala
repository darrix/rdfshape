package es.weso.server

import cats.effect.IO
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import org.scalatest._
import selenium._


class WebServiceTest extends FunSpec
  with Matchers
  with EitherValues
  with BeforeAndAfter
  with HtmlUnit {
  val ip = "0.0.0.0"
  val port = 8080
  val shaclexServer = new RDFShapeServer(ip, port)
  var server: Server[IO] = null

  before {
    println(s"Before tests...starting server...")
    // val builder = BlazeBuilder[IO].bindHttp(port,"localhost").mountService(shaclexServer.service).start
    // server = builder.unsafeRunSync
  }

  after {
    println(s"After tests...closing server and browser...")
    // server.shutdown.unsafeRunSync
    close
    quit
  }

  // val host = s"http://localhost:$port"
  val host = s"http://weso.rdfshape.es"


 /* describe(s"Home page") {

    it(s"Should contain SHACLex") {
      go to (host)
      pageTitle should contain("SHACLex")
    }

  } */
}