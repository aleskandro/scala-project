package controllers

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.model.{StatusCodes}
import com.mongodb.client.model.Filters
import model._
import org.mongodb.scala.bson.BsonObjectId
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import org.mongodb.scala.{Completed, Observer}
import utils.ActorInitializer
import scala.util.{Success, Try, Failure}
import scala.concurrent.{Future}

object TicketHandler{
  def props(): Props = {
    Props(classOf[TicketHandler])
  }
}

/**
  * Case class to match POST requests to /tickets to create a [[model.Ticket]].
  * @param t The [[model.Ticket]] to be created.
  */
case class CreateTicket(t: Ticket)

/**
  * Case class to match GET requests to /tickets
  * @param u The user that performs the requests.
  *
  */
case class GetTickets(u : User)

/**
  * Case class to match GET requests to /ticket/[[id]]
  * @param id The [[model.Ticket]]'s [[org.mongodb.scala.bson.ObjectId]] (passed as a [[String]]) to be searched.
  * @param u The [[model.User]] that performs the request.
  */
case class GetTicket(id : String, u : User)

/**
  * Case class to match DELETE requests to /ticket/[[id]]
  * @param id The [[model.Ticket]]'s [[org.mongodb.scala.bson.ObjectId]] (passed as a [[String]]) to be deleted.
  */
case class DeleteTicket(id : String)

/**
  * The Controller Actor to handling /tickets requests
  */
class TicketHandler extends Actor with ActorLogging {
  import ActorInitializer._
  override def receive: Receive = {
    case req : CreateTicket => // Success or Failure
      // In order to have the sender in the async scope of the observer
      val requester = context.sender()
      val userId = Try(BsonObjectId(req.t.boughtFrom))
      var userFuture : Future[Option[User]] = null
      userId match {
        case Success(userId) =>
          userFuture = Users().find(Filters.eq("_id", userId)).collect().toFuture().map[Option[User]](_.headOption)
        case Failure(error) =>
          println(error.getMessage())
      }

      var eventFuture : Future[Option[Event]] = null
      val eventId = Try(BsonObjectId(req.t.event))
      eventId match {
        case Success(eventId) =>
          eventFuture = Events().find(Filters.eq("_id", eventId)).collect().toFuture().map[Option[Event]](_.headOption)
        case Failure(error) =>
          println(error.getMessage())
      }

      val checkerFuture = for {
        event <- eventFuture;
        user  <- userFuture
      } yield {
        user match {
          case None =>
            None
          case Some(user) =>
            event match {
              case Some(e) =>
                if (e.quantity > 0) {
                  e
                } else {
                  None
                }
              case None => None
            }
        }
      }

      checkerFuture onSuccess {
        case e : Event =>
          // Here we know that event and user exist (if user doesn't exists event is none)
          // Need to decrement event's available tickets and create the Ticket
          Events().replaceOne(Filters.eq("_id", e._id),
            Event(e._id, e.name, e.date, e.category, e.description, e.quantity - 1, e.price))
            .subscribe(new Observer[UpdateResult] {
            override def onError(e: Throwable): Unit =
              log.error(e.getMessage())
            override def onComplete(): Unit = {}
            override def onNext(result: UpdateResult): Unit = {}
          })

          Tickets().insertOne(req.t).subscribe(new Observer[Completed] {
            override def onComplete(): Unit = requester ! StatusCodes.OK
            override def onError(throwable: Throwable) = {
              log.error(throwable.getMessage())
              requester ! StatusCodes.BadRequest
            }
            override def onNext(tResult: Completed) = {}
          })
        case _ =>
          requester ! StatusCodes.BadRequest
      }

    case t : GetTickets => // Array[Ticket]
      val requester = context.sender()
      val user = t.u
      var find = Tickets().find()
      if (!user.isAdmin)
        find = Tickets().find(Filters.eq("boughtFrom", user._id))
      find.collect().subscribe((results: Seq[Ticket]) => {
         requester ! results.toArray
      })
    case t : GetTicket => // Array[Ticket]
      val requester = context.sender()
      val user = t.u
      val id : Try[BsonObjectId] = Try(BsonObjectId(t.id))
      id match {
        case scala.util.Success(id) =>
         var filter = Filters.eq("_id", id)
         if (!user.isAdmin)
           filter = Filters.and(filter, Filters.eq("boughtFrom", user._id))
          Tickets().find(filter).collect().subscribe((results: Seq[Ticket]) => {
            if (results.isEmpty) {
              requester ! StatusCodes.NotFound
            } else {
              requester ! results.toArray
            }
          })
        case scala.util.Failure(_) => requester ! StatusCodes.BadRequest
      }
    case t : DeleteTicket => // Success or Failure
      val requester = context.sender()
      Tickets().deleteOne(Filters.eq("_id", BsonObjectId(t.id))).subscribe(new Observer[DeleteResult] {
        override def onError(e: Throwable): Unit = {
          log.error(e.getMessage())
          requester ! StatusCodes.BadRequest
        }
        override def onComplete(): Unit = requester ! StatusCodes.OK
        override def onNext(result: DeleteResult): Unit = {}
      })
    case _ =>
      sender() ! StatusCodes.InternalServerError
  }

}