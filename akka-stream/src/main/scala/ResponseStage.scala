package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.core.protocol.Response
import reactivemongo.api.Cursor, Cursor.ErrorHandler

private[akkastream] class ResponseStage[T, Out](
  cursor: AkkaStreamCursorImpl[T],
  maxDocs: Int,
  suc: Response => Out,
  err: ErrorHandler[Option[Out]]
)(implicit ec: ExecutionContext)
    extends GraphStage[SourceShape[Out]] {

  override val toString = "ReactiveMongoResponse"
  val out: Outlet[Out] = Outlet(s"${toString}.out")
  val shape: SourceShape[Out] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)
  private val logger = reactivemongo.util.LazyLogger(
    "reactivemongo.akkastream.ResponseStage"
  )

  @inline
  private def next(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var last = Option.empty[(Response, Out)]

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(r) => {
            request = { () =>
              last.fold(Future.successful(Option.empty[Response])) {
                case (lastResponse, _) => next(lastResponse).andThen {
                  case Success(Some(response)) => last.foreach {
                    case (lr, _) =>
                      if (lr.reply.cursorID != response.reply.cursorID) kill(lr)
                  }
                }
              }
            }
          }
        }.map(Some(_))
      }

      private def killLast(): Unit = last.foreach {
        case (r, _) => kill(r)
      }

      @SuppressWarnings(Array("CatchException"))
      private def kill(r: Response): Unit = {
        try {
          cursor.wrappee kill r.reply.cursorID
        } catch {
          case reason: Exception => logger.warn(
            s"fails to kill the cursor (${r.reply.cursorID})", reason
          )
        }

        last = None
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.map(_._2)

        killLast()

        err(previous, reason) match {
          case Cursor.Cont(_)     => ()
          case Cursor.Fail(error) => fail(out, error)
          case Cursor.Done(_)     => completeStage()
        }
      }

      private val futureCB =
        getAsyncCallback((response: Try[Option[Response]]) => {
          response.map(_.map { r => r -> suc(r) }) match {
            case Failure(reason) => onFailure(reason)

            case Success(state @ Some((r, result))) => {
              last = state
              push(out, result)
            }

            case _ =>
              completeStage()
          }
        }).invoke _

      def onPull(): Unit = request().onComplete(futureCB)

      override def postStop(): Unit = {
        killLast()
        super.postStop()
      }

      setHandler(out, this)
    }
}
