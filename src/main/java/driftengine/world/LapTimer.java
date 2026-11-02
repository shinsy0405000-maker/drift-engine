package driftengine.world;

import driftengine.world.map.Course;

/**
 * 코스 문 통과 기록. 문은 순서대로만 인정한다 (지름길·역주행 방지).
 * <ul>
 *   <li>한 바퀴 코스: 출발선(0번) 통과 → 시작, 1..n-1 번 다음 0번을 다시 지나면 한 바퀴 (바로 다음 바퀴 시작)</li>
 *   <li>편도 코스: 0번 통과 → 시작, 마지막 문 통과 → 끝</li>
 * </ul>
 */
public final class LapTimer {

    private final Course course;
    private int next;
    private boolean running;
    private boolean finished;
    private float time;
    private float last = -1;
    private float best = -1;
    private int laps;

    public LapTimer(Course course) {
        this.course = course;
    }

    /** @return 이번에 한 바퀴(편도면 결승)를 마쳤으면 true */
    public boolean update(float dt, float px, float pz, float cx, float cz) {
        if (finished) {
            return false;
        }
        if (running) {
            time += dt;
        }
        int n = course.gates().size();
        int gi = course.loop() && next >= n ? 0 : Math.min(next, n - 1);
        if (!course.gates().get(gi).crossed(px, pz, cx, cz)) {
            return false;
        }
        if (!running) {
            running = true;
            time = 0;
            next = 1;
            return false;
        }
        if (course.loop()) {
            if (next < n) {
                next++;
                return false;
            }
            last = time;
            best = best < 0 ? time : Math.min(best, time);
            laps++;
            time = 0;
            next = 1;
            return true;
        }
        if (next < n - 1) {
            next++;
            return false;
        }
        last = time;
        best = best < 0 ? time : Math.min(best, time);
        laps++;
        finished = true;
        return true;
    }

    public boolean running() {
        return running && !finished;
    }

    public boolean finished() {
        return finished;
    }

    /** 지금 바퀴 시간 (출발 전 0) */
    public float time() {
        return time;
    }

    /** 바로 전 바퀴 (없으면 -1) */
    public float last() {
        return last;
    }

    public float best() {
        return best;
    }

    public int laps() {
        return laps;
    }

    /** 통과한 문 수 / 전체 (진행 표시용) */
    public int passed() {
        return running ? next : 0;
    }

    public int total() {
        return course.gates().size();
    }

    public Course course() {
        return course;
    }
}
