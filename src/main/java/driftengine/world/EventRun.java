package driftengine.world;

import driftengine.game.ClubEvent;
import driftengine.world.map.Course;

/**
 * 클럽 이벤트 한 판: 출발 카운트다운(3초) → 주행 → 결과 표시(6초).
 * <ul>
 *   <li>TIMED: 연습장·도시 드리프트, 60초 동안 점수</li>
 *   <li>COURSE: 고갯길 드리프트, 결승선까지 점수 (제한 시간이 지나면 그때 점수)</li>
 *   <li>LAP: 서킷 랩타임, 출발선부터 한 바퀴 (제한 시간을 넘기면 실패)</li>
 * </ul>
 * 점수는 TerrainScene 의 DriftScorer 를 출발 순간 비우고 그대로 쓴다.
 */
final class EventRun {

    enum Phase { COUNTDOWN, RUNNING, RESULT, DONE }

    enum Mode { TIMED, COURSE, LAP }

    static final float COUNTDOWN = 3f;
    static final float DRIFT_TIME = 60f;
    static final float RESULT_TIME = 6f;
    /** 고갯길 제한 시간 (초) */
    static final float COURSE_LIMIT = 300f;

    final ClubEvent event;
    final String clubName;
    final Mode mode;
    final LapTimer gates;
    Phase phase = Phase.COUNTDOWN;
    /** COUNTDOWN: 남은 초, TIMED: 남은 초, COURSE·LAP: 지난 초 */
    float timer = COUNTDOWN;
    float result;
    int stars;
    String message = "";
    boolean failed;

    EventRun(ClubEvent event, String clubName) {
        this(event, clubName, null);
    }

    EventRun(ClubEvent event, String clubName, Course course) {
        this.event = event;
        this.clubName = clubName;
        if (course == null) {
            mode = Mode.TIMED;
        } else {
            mode = event.type() == ClubEvent.Type.LAP_TIME ? Mode.LAP : Mode.COURSE;
        }
        gates = course == null ? null : new LapTimer(course);
    }

    /** 랩 이벤트 제한 시간: 목표의 2배 + 출발선까지 20초 */
    float lapLimit() {
        return event.target() * 2 + 20;
    }

    /**
     * @param px 이전 위치 x  @param cx 지금 위치 x (문 통과 판정)
     * @return 이번에 RUNNING 이 끝났으면 true (결과 계산할 때)
     */
    boolean update(float dt, float px, float pz, float cx, float cz) {
        switch (phase) {
            case COUNTDOWN -> {
                timer -= dt;
                if (timer <= 0) {
                    phase = Phase.RUNNING;
                    timer = mode == Mode.TIMED ? DRIFT_TIME : 0;
                }
            }
            case RUNNING -> {
                if (mode == Mode.TIMED) {
                    timer -= dt;
                    if (timer <= 0) {
                        return finish(Float.NaN);
                    }
                    return false;
                }
                timer += dt;
                boolean done = gates.update(dt, px, pz, cx, cz);
                if (done) {
                    return finish(mode == Mode.LAP ? gates.last() : Float.NaN);
                }
                if (mode == Mode.LAP && timer > lapLimit()) {
                    failed = true;
                    return finish(0);
                }
                if (mode == Mode.COURSE && timer > COURSE_LIMIT) {
                    return finish(Float.NaN);
                }
            }
            case RESULT -> {
                timer -= dt;
                if (timer <= 0) {
                    phase = Phase.DONE;
                }
            }
            default -> {
            }
        }
        return false;
    }

    /** lapResult: 랩 시간 (NaN 이면 점수 이벤트 → TerrainScene 이 점수를 넣음) */
    private boolean finish(float lapResult) {
        if (!Float.isNaN(lapResult)) {
            result = lapResult;
        }
        phase = Phase.RESULT;
        timer = RESULT_TIME;
        return true;
    }

    boolean controlsLocked() {
        return phase == Phase.COUNTDOWN;
    }

    /** 점수로 겨루나 (랩타임이 아니면) */
    boolean scoreEvent() {
        return mode != Mode.LAP;
    }
}
