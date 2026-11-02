package driftengine.upscale;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;

/**
 * 시간적 업스케일러(DLSS)에 매 프레임 넘기는 카메라 정보. 행렬은 모두 지터 없음(Streamline 문서 요구).
 * <p>
 * 행렬 배열은 이 엔진 행렬의 열 우선 배열이며, 그대로 Streamline 규약(행 우선, 행 벡터)의 행렬이 된다
 * (Mat4.toColumnMajorArray 설명 참고).
 *
 * @param viewToClip     투영 (카메라 공간 → 클립)
 * @param clipToView     투영의 역행렬
 * @param clipToPrevClip 현재 클립 → 이전 프레임 클립 = 이전 viewProj × 현재 viewProj⁻¹
 * @param prevClipToClip clipToPrevClip 의 역행렬
 * @param jitterX        렌더 해상도 픽셀 단위 지터 (+ 는 화면 오른쪽)
 * @param jitterY        렌더 해상도 픽셀 단위 지터 (+ 는 화면 아래쪽)
 * @param fovY           세로 시야각 (라디안)
 */
public record UpscaleCamera(float[] viewToClip, float[] clipToView, float[] clipToPrevClip, float[] prevClipToClip,
                            float jitterX, float jitterY,
                            Vec3 position, Vec3 up, Vec3 right, Vec3 forward,
                            float near, float far, float fovY, float aspect) {

    /** Camera.update 를 마친 뒤 호출한다. */
    public static UpscaleCamera from(Camera cam) {
        Mat4 clipToView = inverseOrIdentity(cam.projection());
        Mat4 clipToPrevClip = new Mat4().mul(cam.previousViewProjection(), inverseOrIdentity(cam.viewProjection()));
        Mat4 prevClipToClip = inverseOrIdentity(clipToPrevClip);
        return new UpscaleCamera(
                cam.projection().toColumnMajorArray(),
                clipToView.toColumnMajorArray(),
                clipToPrevClip.toColumnMajorArray(),
                prevClipToClip.toColumnMajorArray(),
                cam.jitterX(), cam.jitterY(),
                cam.position(), cam.up(), cam.right(), cam.forward(),
                cam.near(), cam.far(), cam.fovY(), cam.aspect());
    }

    private static Mat4 inverseOrIdentity(Mat4 m) {
        Mat4 inv = new Mat4();
        if (!inv.invert(m)) {
            inv.identity();
        }
        return inv;
    }
}
