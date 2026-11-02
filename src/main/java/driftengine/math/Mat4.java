package driftengine.math;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * 4x4 행렬 (가변, float). 매 프레임 새로 만들지 않고 재사용하기 위해 가변으로 둔다.
 * <p>
 * 저장 순서: 열 우선(column-major). GLSL/SPIR-V 의 기본 mat4 와 같아서 그대로 GPU 에 올릴 수 있다.
 * (행 r, 열 c) 원소는 m[c * 4 + r].
 * <p>
 * 투영 규칙: Vulkan — 깊이 0(near)~1(far), 화면 y 는 아래쪽이 +.
 * Streamline(DLSS) 은 행 우선(row-major) 행렬을 요구하므로 넘길 때는 storeRowMajor 를 쓴다.
 */
public final class Mat4 {

    private final float[] m = new float[16];

    public Mat4() {
        identity();
    }

    public Mat4(Mat4 other) {
        set(other);
    }

    // ------------------------------------------------------------------ 기본 조작

    public float get(int row, int col) {
        return m[col * 4 + row];
    }

    public Mat4 set(int row, int col, float value) {
        m[col * 4 + row] = value;
        return this;
    }

    public Mat4 set(Mat4 other) {
        System.arraycopy(other.m, 0, m, 0, 16);
        return this;
    }

    public Mat4 identity() {
        Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
        return this;
    }

    /** this = a × b (a 나 b 가 this 여도 안전) */
    public Mat4 mul(Mat4 a, Mat4 b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a.m[k * 4 + row] * b.m[col * 4 + k];
                }
                r[col * 4 + row] = sum;
            }
        }
        System.arraycopy(r, 0, m, 0, 16);
        return this;
    }

    // ------------------------------------------------------------------ 변환 행렬

    public Mat4 translation(float x, float y, float z) {
        identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return this;
    }

    public Mat4 scaling(float x, float y, float z) {
        identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return this;
    }

    /**
     * 단위 쿼터니언 (x, y, z, w) 의 회전 행렬 (물리 엔진의 물체 방향을 그릴 때).
     * 길이가 1 이 아니어도 정규화해서 쓴다.
     */
    public Mat4 rotation(float qx, float qy, float qz, float qw) {
        float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (len < 1e-12f) {
            return identity();
        }
        float x = qx / len;
        float y = qy / len;
        float z = qz / len;
        float w = qw / len;
        identity();
        set(0, 0, 1 - 2 * (y * y + z * z));
        set(0, 1, 2 * (x * y - z * w));
        set(0, 2, 2 * (x * z + y * w));
        set(1, 0, 2 * (x * y + z * w));
        set(1, 1, 1 - 2 * (x * x + z * z));
        set(1, 2, 2 * (y * z - x * w));
        set(2, 0, 2 * (x * z - y * w));
        set(2, 1, 2 * (y * z + x * w));
        set(2, 2, 1 - 2 * (x * x + y * y));
        return this;
    }

    /** Y 축 회전 (라디안, 위에서 볼 때 반시계 방향이 +) */
    public Mat4 rotationY(float radians) {
        identity();
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        set(0, 0, c);
        set(0, 2, s);
        set(2, 0, -s);
        set(2, 2, c);
        return this;
    }

    /**
     * 카메라 뷰 행렬 (오른손 좌표계). 뷰 공간에서 카메라는 -Z 를 바라보고, +X 가 오른쪽, +Y 가 위.
     */
    public Mat4 lookAt(Vec3 eye, Vec3 target, Vec3 up) {
        Vec3 f = target.sub(eye).normalize();      // 앞
        Vec3 s = f.cross(up).normalize();          // 오른쪽
        Vec3 u = s.cross(f);                       // 위
        identity();
        set(0, 0, s.x()).set(0, 1, s.y()).set(0, 2, s.z()).set(0, 3, -s.dot(eye));
        set(1, 0, u.x()).set(1, 1, u.y()).set(1, 2, u.z()).set(1, 3, -u.dot(eye));
        set(2, 0, -f.x()).set(2, 1, -f.y()).set(2, 2, -f.z()).set(2, 3, f.dot(eye));
        return this;
    }

    /**
     * Vulkan 용 원근 투영.
     *
     * @param fovYRadians 세로 시야각 (라디안)
     * @param aspect      가로/세로 비율
     * @param near        가까운 평면 (> 0)
     * @param far         먼 평면 (> near)
     */
    public Mat4 perspectiveVulkan(float fovYRadians, float aspect, float near, float far) {
        if (near <= 0 || far <= near || aspect <= 0) {
            throw new IllegalArgumentException("잘못된 투영 값: near=" + near + " far=" + far + " aspect=" + aspect);
        }
        float f = (float) (1.0 / Math.tan(fovYRadians / 2.0));
        Arrays.fill(m, 0);
        set(0, 0, f / aspect);
        set(1, 1, -f);                          // Vulkan 은 화면 y 가 아래로 증가 → 뒤집기
        set(2, 2, far / (near - far));          // 깊이 0..1
        set(2, 3, near * far / (near - far));
        set(3, 2, -1);                          // w = -z_view
        return this;
    }

    /**
     * Vulkan 용 직교 투영 (그림자 맵). 뷰 공간 -Z 방향을 본다. 깊이 near → 0, far → 1.
     * y 는 뒤집지 않는다: 그림자 맵을 그릴 때와 읽을 때 같은 행렬을 쓰므로 uv = ndc·0.5 + 0.5 로 맞는다.
     */
    public Mat4 orthoVulkan(float halfWidth, float halfHeight, float near, float far) {
        if (halfWidth <= 0 || halfHeight <= 0 || far <= near) {
            throw new IllegalArgumentException("잘못된 직교 투영 값: " + halfWidth + " " + halfHeight + " " + near + " " + far);
        }
        Arrays.fill(m, 0);
        set(0, 0, 1 / halfWidth);
        set(1, 1, 1 / halfHeight);
        set(2, 2, -1 / (far - near));
        set(2, 3, -near / (far - near));
        set(3, 3, 1);
        return this;
    }

    /**
     * 투영 결과를 NDC 에서 (dx, dy) 만큼 옮긴다. DLSS 지터에 사용한다.
     * 어떤 투영 행렬에도 적용 가능 (NDC 이동 행렬을 왼쪽에 곱하는 것과 같다).
     */
    public Mat4 applyNdcOffset(float dx, float dy) {
        for (int col = 0; col < 4; col++) {
            float w = get(3, col);
            set(0, col, get(0, col) + dx * w);
            set(1, col, get(1, col) + dy * w);
        }
        return this;
    }

    /**
     * this = src 의 역행렬. 역행렬이 없으면(행렬식 ≈ 0) false 를 돌려주고 this 는 바뀌지 않는다.
     * 여인수 전개 방식 (일반 4x4).
     */
    public boolean invert(Mat4 src) {
        float[] a = src.m;
        float[] inv = new float[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];

        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-12f) {
            return false;
        }
        float invDet = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            m[i] = inv[i] * invDet;
        }
        return true;
    }

    // ------------------------------------------------------------------ 점 변환

    /** (x, y, z, 1) 을 변환한 동차 좌표 [x, y, z, w] */
    public float[] transform(float x, float y, float z, float w) {
        float[] r = new float[4];
        for (int row = 0; row < 4; row++) {
            r[row] = get(row, 0) * x + get(row, 1) * y + get(row, 2) * z + get(row, 3) * w;
        }
        return r;
    }

    /** 점을 변환한 뒤 w 로 나눈다 (투영 행렬이면 NDC 좌표가 된다). */
    public Vec3 transformPoint(Vec3 p) {
        float[] r = transform(p.x(), p.y(), p.z(), 1);
        return new Vec3(r[0] / r[3], r[1] / r[3], r[2] / r[3]);
    }

    /** 방향 벡터 변환 (이동 무시, w = 0) */
    public Vec3 transformDirection(Vec3 d) {
        float[] r = transform(d.x(), d.y(), d.z(), 0);
        return new Vec3(r[0], r[1], r[2]);
    }

    // ------------------------------------------------------------------ 내보내기

    /** GPU(GLSL) 용: 열 우선 16개 float */
    public FloatBuffer storeColumnMajor(FloatBuffer dst) {
        return dst.put(m);
    }

    /**
     * 열 우선 16개 float 복사본.
     * <p>
     * Streamline(DLSS) 상수에는 이것을 넘긴다. Streamline 은 "행 우선 저장 + 행 벡터(v*M)" 규약이고
     * (sl_matrix_helpers.h: 이동 성분이 4번째 행, clipToPrevClip = clipToView * viewToPrevView * prevViewToClip),
     * 이 엔진은 "열 벡터(M*v)" 규약이므로 Streamline 행렬 = 이 행렬의 전치이다.
     * 전치 행렬의 행 우선 배열은 원래 행렬의 열 우선 배열과 같다.
     */
    public float[] toColumnMajorArray() {
        return m.clone();
    }

    /** 행 우선 16개 float (사람이 읽는 순서). Streamline 에는 toColumnMajorArray 를 쓴다. */
    public float[] toRowMajorArray() {
        float[] r = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                r[row * 4 + col] = get(row, col);
            }
        }
        return r;
    }

    public boolean approxEquals(Mat4 o, float eps) {
        for (int i = 0; i < 16; i++) {
            if (Math.abs(m[i] - o.m[i]) > eps) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 4; row++) {
            sb.append(String.format("[%9.4f %9.4f %9.4f %9.4f]%n", get(row, 0), get(row, 1), get(row, 2), get(row, 3)));
        }
        return sb.toString();
    }
}
