package driftengine.scene.mesh;

import driftengine.math.Vec3;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * CPU 쪽 메시 데이터 (불변). GPU 버퍼에 올리기 직전 형태다.
 * <p>
 * 삼각형 감기 방향 규칙: 바깥(법선 쪽)에서 볼 때 반시계 방향(CCW), 오른손 좌표계.
 *
 * @param name     디버그용 이름
 * @param vertices VertexFormat 배치의 float 배열
 * @param indices  삼각형 정점 번호 (3개씩)
 */
public record MeshData(String name, float[] vertices, int[] indices) {

    public MeshData {
        if (vertices.length % VertexFormat.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(name + ": 정점 배열 길이가 " + VertexFormat.FLOATS_PER_VERTEX + "의 배수가 아님");
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException(name + ": 인덱스 수가 3의 배수가 아님");
        }
        int vertexCount = vertices.length / VertexFormat.FLOATS_PER_VERTEX;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException(name + ": 잘못된 인덱스 " + index + " (정점 " + vertexCount + "개)");
            }
        }
        vertices = vertices.clone(); // 외부 배열이 바뀌어도 영향 없게 복사
        indices = indices.clone();
    }

    public int vertexCount() {
        return vertices.length / VertexFormat.FLOATS_PER_VERTEX;
    }

    public int indexCount() {
        return indices.length;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public Vec3 position(int vertex) {
        return attribute(vertex, 0);
    }

    public Vec3 normal(int vertex) {
        return attribute(vertex, 3);
    }

    public Vec3 color(int vertex) {
        return attribute(vertex, 6);
    }

    /** 재질 {금속성, 거칠기, 클리어코트, 발광, 표면 종류} */
    public float[] material(int vertex) {
        int base = vertex * VertexFormat.FLOATS_PER_VERTEX + 9;
        return Arrays.copyOfRange(vertices, base, base + 5);
    }

    /** 내부 배열 (복사 없이 읽기 전용으로 쓴다) */
    float[] vertexArray() {
        return vertices;
    }

    int[] indexArray() {
        return indices;
    }

    private Vec3 attribute(int vertex, int offset) {
        int base = vertex * VertexFormat.FLOATS_PER_VERTEX + offset;
        return new Vec3(vertices[base], vertices[base + 1], vertices[base + 2]);
    }

    /** 모든 정점을 감싸는 상자의 최소 모서리 */
    public Vec3 boundsMin() {
        float x = Float.MAX_VALUE, y = Float.MAX_VALUE, z = Float.MAX_VALUE;
        for (int i = 0; i < vertexCount(); i++) {
            Vec3 p = position(i);
            x = Math.min(x, p.x());
            y = Math.min(y, p.y());
            z = Math.min(z, p.z());
        }
        return new Vec3(x, y, z);
    }

    /** 모든 정점을 감싸는 상자의 최대 모서리 */
    public Vec3 boundsMax() {
        float x = -Float.MAX_VALUE, y = -Float.MAX_VALUE, z = -Float.MAX_VALUE;
        for (int i = 0; i < vertexCount(); i++) {
            Vec3 p = position(i);
            x = Math.max(x, p.x());
            y = Math.max(y, p.y());
            z = Math.max(z, p.z());
        }
        return new Vec3(x, y, z);
    }

    public long vertexBytes() {
        return (long) vertices.length * Float.BYTES;
    }

    public long indexBytes() {
        return (long) indices.length * Integer.BYTES;
    }

    /** GPU 업로드용 버퍼에 정점을 쓴다 (나중에 정점 버퍼 단계에서 사용). */
    public FloatBuffer writeVertices(FloatBuffer dst) {
        return dst.put(vertices);
    }

    public IntBuffer writeIndices(IntBuffer dst) {
        return dst.put(indices);
    }
}
