package org.unict.ing.advlanguages.boxoffice


import akka.actor.ActorSystem
import akka.actor.Status.Success
import akka.http.scaladsl.model.{DateTime, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.pattern.ask
import model.Event
import org.bson.types.ObjectId
import controllers._
import devTests._

import scala.concurrent.duration._
import scala.io.StdIn
import spray.json.{DefaultJsonProtocol, DeserializationException, JsString, JsValue, RootJsonFormat}

import scala.util.Try


trait JsonUnMarshall extends SprayJsonSupport with DefaultJsonProtocol {

  // Conversion of DateTime in Json format, see:
  // https://stackoverflow.com/questions/25178108/converting-datetime-to-a-json-string
  implicit object DateJsonFormat extends RootJsonFormat[DateTime] {
    override def write(obj: DateTime) = JsString(obj.toIsoDateTimeString())

    override def read(json: JsValue): DateTime = {
      val x = DateTime.fromIsoDateTimeString(json.convertTo[String])
      x match {
        case Some(d) => d
        case None => throw new DeserializationException("Error on date deserialization")
      }
    }
  }

  implicit object ObjectIdFormat extends RootJsonFormat[ObjectId] {
    override def write(obj: ObjectId) = JsString(obj.toString())

    override def read(json: JsValue): ObjectId = {
      Try(new ObjectId(json.convertTo[String])) match {
        case scala.util.Success(oid) => oid
        case scala.util.Failure(_) => new ObjectId()
      }
    }
  }
  // implicit variables needed to the marshalling/unmarshalling of the case classes
  implicit val eventFormat  = jsonFormat5(Event)
  implicit val userFormat   = jsonFormat4(User)
  implicit val healthFormat = jsonFormat2(Health)
}


object Main extends JsonUnMarshall {

  val host = "localhost"
  val port = 8080

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val timeout = Timeout(20.seconds)

    // Initialization of the actors
    val requestHandler = system.actorOf(RequestHandler.props(), "requestHandler")
    val eventHandler   = system.actorOf(EventHandler.props(), "eventHandler")
    val userHandler    = system.actorOf(UserHandler.props(), "userHandler")

    // Route
    val route : Route = {

      path("ping") {
        get {
          onSuccess(requestHandler ? GetHealthRequest) {
            case response: HealthResponse =>
              complete(StatusCodes.OK, response.health)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
          post {
            entity(as[Health]) {
              statusReport =>
                onSuccess(requestHandler ? SetStatusRequest(statusReport)) {
                  case response: HealthResponse =>
                    complete(StatusCodes.OK, response.health)
                  case _ =>
                    complete(StatusCodes.InternalServerError)
                }
            }
          }
      } ~
      path("events") {
        get {
          onSuccess(eventHandler ? GetEvents()) {
            case response: Array[Event] =>
              complete(StatusCodes.OK, response)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
          post {
            entity(as[Event]) {
              event =>
                onSuccess(eventHandler ? CreateEvent(event)) {
                  case Success =>
                    complete(StatusCodes.OK)
                  case _ =>
                    complete(StatusCodes.InternalServerError)
                }
            }
          }
      } ~
      path("events" / Segment) { eventId =>
        get {
          onSuccess(eventHandler ? GetEvent(eventId)) {
            case r : Array[Event] =>
              complete(StatusCodes.OK, r)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
        put {
          entity(as[Event]) { event =>
            onSuccess(eventHandler ? PutEvent(eventId, event)) {
              case Success =>
                complete(StatusCodes.OK)
              case a: Any =>
                print(a)
                complete(StatusCodes.InternalServerError)
            }
          }
        } ~
        delete {
          onSuccess(eventHandler ? DeleteEvent(eventId)) {
            case Success =>
              complete(StatusCodes.OK)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        }
      } ~
      path("users") {
        get {
          onSuccess(userHandler ? GetUserList) {
            case response: UsersResponse =>
              complete(StatusCodes.OK, response.users)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
          post {
            entity(as[User]) {
              user =>
                onSuccess(userHandler ? CreateUser(user)) {
                  case response: User =>
                    complete(StatusCodes.OK, response)
                  case _ =>
                    complete(StatusCodes.InternalServerError)
                }
            }
          }
      } ~
      path("users" / LongNumber) { userId =>
        get {
          onSuccess(userHandler ? GetSingleUser(userId)) {
            case response: SingleUserResponse =>
              Console.println(s"Getting user with id #$userId...")
              if(response.user == null) {
                Console.println("User ID not found.")
                complete(StatusCodes.NotFound)
              } else {
                complete(StatusCodes.OK, response.user)
              }
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
        delete {
          // delete a user and return the user itself
          onSuccess(userHandler ? DeleteUser(userId)) {
            case response: DeleteUserResponse =>
              Console.println(s"Deleting user with id #$userId...")
              if(response.user == null) {
                complete(StatusCodes.NotFound)
              } else {
                complete(StatusCodes.OK, response.user)
              }
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        }
      }
    }

    val bindingFuture = Http().bindAndHandle(route, host, port)
    println("API on...")
    StdIn.readLine()
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }

}
