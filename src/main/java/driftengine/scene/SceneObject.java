package driftengine.scene;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;

/**
 * 장면 안의 물체 하나: 모양(메시) + 위치/회전/크기(모델 행렬).
 * 같은 메시를 여러 물체가 공유할 수 있다.
 */
public final class SceneObject {

    private final String name;
    private MeshData mesh;
    private final Mat4 model = new Mat4();
    private final Mat4 previousModel = new Mat4();
    private boolean hasPrevious;
    private boolean visible = true;
    private boolean castsShadow = true;
    private boolean transparent;

    public SceneObject(String name, MeshData mesh) {
        this.name = name;
        this.mesh = mesh;
    }

    public String name() {
        return name;
    }

    public MeshData mesh() {
        return mesh;
    }

    /** 모양 바꾸기 (차 바꾸기, 바디킷). 렌더러가 처음 보는 메시는 그릴 때 GPU 에 올린다 */
    public SceneObject setMesh(MeshData mesh) {
        this.mesh = mesh;
        return this;
    }

    /** 모델 행렬 (로컬 좌표 → 월드 좌표). 렌더러가 읽는다. */
    public Mat4 model() {
        return model;
    }

    /** 직전 프레임에 그렸을 때의 모델 행렬 (모션 벡터 계산용) */
    public Mat4 previousModel() {
        return previousModel;
    }

    /**
     * 새 프레임을 시작할 때 호출: 지금 행렬을 "이전 프레임"으로 보관한다.
     * 처음 호출이면 이전 = 현재 (움직임 없음).
     */
    public void beginFrame() {
        previousModel.set(model);
        hasPrevious = true;
    }

    public boolean hasPrevious() {
        return hasPrevious;
    }

    /** 안 보이면 렌더러가 그리지 않는다 (차 여러 대 중 지금 차만 보이기 등) */
    public boolean visible() {
        return visible;
    }

    public SceneObject setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** 해 그림자를 드리우나 (지형·바닥처럼 크고 평평한 것은 끈다: 그림자 맵에 그릴 삼각형을 줄임) */
    public boolean castsShadow() {
        return castsShadow;
    }

    /** 투명 (차 유리 등): 불투명 물체 다음 투명 단계에서 먼 것부터 그린다. 그림자를 드리우지 않음 */
    public boolean transparent() {
        return transparent;
    }

    public SceneObject setTransparent(boolean t) {
        this.transparent = t;
        return this;
    }

    public SceneObject setCastsShadow(boolean casts) {
        this.castsShadow = casts;
        return this;
    }

    /** 위치 + Y축 회전 + 크기로 모델 행렬을 설정한다 (적용 순서: 크기 → 회전 → 이동). */
    public SceneObject place(Vec3 position, float yawRadians, Vec3 scale) {
        Mat4 rotate = new Mat4().rotationY(yawRadians);
        Mat4 size = new Mat4().scaling(scale.x(), scale.y(), scale.z());
        model.translation(position.x(), position.y(), position.z());
        model.mul(model, rotate);
        model.mul(model, size);
        if (!hasPrevious) {
            previousModel.set(model); // 처음 배치할 때는 움직임이 없는 것으로
        }
        return this;
    }

    /** 위치 + 쿼터니언 회전 + 크기 (물리 엔진이 움직이는 물체). 적용 순서: 크기 → 회전 → 이동. */
    public SceneObject place(Vec3 position, float qx, float qy, float qz, float qw, Vec3 scale) {
        Mat4 rotate = new Mat4().rotation(qx, qy, qz, qw);
        Mat4 size = new Mat4().scaling(scale.x(), scale.y(), scale.z());
        model.translation(position.x(), position.y(), position.z());
        model.mul(model, rotate);
        model.mul(model, size);
        if (!hasPrevious) {
            previousModel.set(model);
        }
        return this;
    }

    /** 월드 공간에서 물체의 가장 낮은 높이 (바닥에 놓였는지 확인용) */
    public float worldMinY() {
        float min = Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            min = Math.min(min, model.transformPoint(mesh.position(i)).y());
        }
        return min;
    }
}
