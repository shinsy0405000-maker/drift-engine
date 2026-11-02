package driftengine.scene;

/**
 * 반투명 효과 목록 (한 프레임 분량): 스키드 자국 사각형 + 연기 입자. 장면이 채우고 렌더러가 읽는다.
 * 배열을 재사용해 프레임마다 쓰레기를 만들지 않는다. 렌더러는 스키드 자국을 먼저, 연기를 먼 것부터 그린다.
 */
public final class Effects {

    public static final int MAX_SKID = 6000;
    public static final int MAX_SMOKE = 3000;
    /** 스키드 한 조각: 네 모서리(왼A, 오른A, 오른B, 왼B) xyz 12 + 진하기 + 길이 방향 v 시작·끝 */
    public static final int SKID_FLOATS = 15;
    /** 연기 한 알: 중심 xyz, 크기(반 폭), 진하기, 회전, 밝기, 무늬 씨앗 */
    public static final int SMOKE_FLOATS = 8;

    private final float[] skid = new float[MAX_SKID * SKID_FLOATS];
    private final float[] smoke = new float[MAX_SMOKE * SMOKE_FLOATS];
    private int skidCount;
    private int smokeCount;

    public void clear() {
        skidCount = 0;
        smokeCount = 0;
    }

    /** 꽉 차면 false (더 넣지 않음) */
    public boolean addSkid(float[] corners12, float alpha, float v0, float v1) {
        if (skidCount >= MAX_SKID) {
            return false;
        }
        int o = skidCount * SKID_FLOATS;
        System.arraycopy(corners12, 0, skid, o, 12);
        skid[o + 12] = alpha;
        skid[o + 13] = v0;
        skid[o + 14] = v1;
        skidCount++;
        return true;
    }

    public boolean addSmoke(float x, float y, float z, float size, float alpha, float rotation, float shade, float seed) {
        if (smokeCount >= MAX_SMOKE) {
            return false;
        }
        int o = smokeCount * SMOKE_FLOATS;
        smoke[o] = x;
        smoke[o + 1] = y;
        smoke[o + 2] = z;
        smoke[o + 3] = size;
        smoke[o + 4] = alpha;
        smoke[o + 5] = rotation;
        smoke[o + 6] = shade;
        smoke[o + 7] = seed;
        smokeCount++;
        return true;
    }

    public int skidCount() {
        return skidCount;
    }

    public int smokeCount() {
        return smokeCount;
    }

    /** 읽기 전용으로 쓴다 (렌더러) */
    public float[] skidData() {
        return skid;
    }

    public float[] smokeData() {
        return smoke;
    }

    public boolean isEmpty() {
        return skidCount == 0 && smokeCount == 0;
    }
}
