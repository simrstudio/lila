package lila.tournament

import play.api.i18n.Lang
import play.api.libs.json._

import lila.common.Json.jodaWrites
import lila.rating.PerfType
import lila.user.LightUserApi

final class ApiJsonView(lightUserApi: LightUserApi)(implicit ec: scala.concurrent.ExecutionContext) {

  import JsonView._

  def apply(tournaments: VisibleTournaments)(implicit lang: Lang): Fu[JsObject] =
    for {
      created  <- tournaments.created.collect(visibleJson).sequenceFu
      started  <- tournaments.started.collect(visibleJson).sequenceFu
      finished <- tournaments.finished.collect(visibleJson).sequenceFu
    } yield Json.obj(
      "created"  -> created,
      "started"  -> started,
      "finished" -> finished
    )

  private def visibleJson(implicit lang: Lang): PartialFunction[Tournament, Fu[JsObject]] = {
    case tour if tour.teamBattle.fold(true)(_.hasEnoughTeams) => fullJson(tour)
  }

  def featured(tournaments: List[Tournament])(implicit lang: Lang): Fu[JsObject] =
    tournaments.map(fullJson).sequenceFu map { objs =>
      Json.obj("featured" -> objs)
    }

  def calendar(tournaments: List[Tournament])(implicit lang: Lang): JsObject = Json.obj(
    "since"       -> tournaments.headOption.map(_.startsAt.withTimeAtStartOfDay),
    "to"          -> tournaments.lastOption.map(_.finishesAt.withTimeAtStartOfDay plusDays 1),
    "tournaments" -> JsArray(tournaments.map(baseJson))
  )

  private def baseJson(tour: Tournament)(implicit lang: Lang): JsObject =
    Json
      .obj(
        "id"        -> tour.id,
        "createdBy" -> tour.createdBy,
        "system"    -> "arena", // BC
        "minutes"   -> tour.minutes,
        "clock"     -> tour.clock,
        "rated"     -> tour.mode.rated,
        "fullName"  -> tour.name(),
        "nbPlayers" -> tour.nbPlayers,
        "variant" -> Json.obj(
          "key"   -> tour.variant.key,
          "short" -> tour.variant.shortName,
          "name"  -> tour.variant.name
        ),
        "secondsToStart" -> tour.secondsToStart,
        "startsAt"       -> tour.startsAt,
        "finishesAt"     -> tour.finishesAt,
        "status"         -> tour.status.id,
        "perf"           -> tour.perfType.map(perfJson)
      )
      .add("hasMaxRating", tour.conditions.maxRating.isDefined)
      .add("private", tour.isPrivate)
      .add("position", tour.position.some.filterNot(_.initial) map positionJson)
      .add("schedule", tour.schedule map scheduleJson)
      .add("battle", tour.teamBattle.map(_ => Json.obj()))

  def fullJson(tour: Tournament)(implicit lang: Lang): Fu[JsObject] =
    for {
      owner  <- tour.nonLichessCreatedBy ?? lightUserApi.async
      winner <- tour.winnerId ?? lightUserApi.async
    } yield baseJson(tour) ++ Json
      .obj(
        "winner" -> winner.map(userJson)
      )
      .add("major", owner.exists(_.title.isDefined))

  private def userJson(u: lila.common.LightUser) = Json.obj(
    "id"    -> u.id,
    "name"  -> u.name,
    "title" -> u.title
  )

  private val perfPositions: Map[PerfType, Int] = {
    import PerfType._
    List(Bullet, Blitz, Rapid, UltraBullet) ::: variants
  }.zipWithIndex.toMap

  private def perfJson(p: PerfType)(implicit lang: Lang) = Json.obj(
    "icon"     -> p.iconChar.toString,
    "key"      -> p.key,
    "name"     -> p.trans,
    "position" -> ~perfPositions.get(p)
  )

}
