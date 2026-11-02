package driftengine.scene.mesh;

import driftengine.math.Vec3;

import java.util.Arrays;

/**
 * MeshData 를 조금씩 쌓아 만드는 도구. 배열이 모자라면 자동으로 늘린다.
 */
public final class MeshBuilder {

    private final String name;
    private float[] vertices = new float[VertexFormat.FLOATS_PER_VERTEX * 64];
    private int[] indices = new int[96];
    private int floatCount;
    private int indexCount;
    private Material material = Material.DEFAULT;

    public MeshBuilder(String name) {
        this.name = name;
    }

    /** 이후 추가하는 정점의 재질 */
    public MeshBuilder material(Material m) {
        this.material = m;
        return this;
    }

    public Material material() {
        return material;
    }

    /** 지금까지 쌓은 정점 배열 (복사 없이, 읽기 전용으로 쓸 것. 정점을 더 넣으면 바뀔 수 있음) */
    public float[] peekVertices() {
        return vertices;
    }

    /** 지금까지 추가한 정점 수 */
    public int vertexCount() {
        return floatCount / VertexFormat.FLOATS_PER_VERTEX;
    }

    /** @return 추가한 정점의 번호 (재질은 material(...) 로 정한 현재 재질) */
    public int vertex(Vec3 position, Vec3 normal, Vec3 color) {
        if (floatCount + VertexFormat.FLOATS_PER_VERTEX > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
        }
        int index = floatCount / VertexFormat.FLOATS_PER_VERTEX;
        put(position);
        put(normal);
        put(color);
        vertices[floatCount++] = material.metallic();
        vertices[floatCount++] = material.roughness();
        vertices[floatCount++] = material.clearcoat();
        vertices[floatCount++] = material.emissive();
        vertices[floatCount++] = material.surface();
        return index;
    }

    private void put(Vec3 v) {
        vertices[floatCount++] = v.x();
        vertices[floatCount++] = v.y();
        vertices[floatCount++] = v.z();
    }

    /** 삼각형 a → b → c (바깥에서 볼 때 반시계) */
    public MeshBuilder triangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
        return this;
    }

    /** 사각형 a → b → c → d (바깥에서 볼 때 반시계)를 삼각형 2개로 */
    public MeshBuilder quad(int a, int b, int c, int d) {
        return triangle(a, b, c).triangle(a, c, d);
    }

    /** 같은 법선과 색을 가진 사각형 면 하나를 추가한다 (모서리 4개, 반시계 순서). */
    public MeshBuilder face(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 normal, Vec3 color) {
        int a = vertex(p0, normal, color);
        int b = vertex(p1, normal, color);
        int c = vertex(p2, normal, color);
        int d = vertex(p3, normal, color);
        return quad(a, b, c, d);
    }

    /** 정점 위치 읽기 (법선 다시 계산 등) */
    public Vec3 position(int vertex) {
        int b = vertex * VertexFormat.FLOATS_PER_VERTEX;
        return new Vec3(vertices[b], vertices[b + 1], vertices[b + 2]);
    }

    /** 정점 법선 덮어쓰기 */
    public void setNormal(int vertex, Vec3 n) {
        int b = vertex * VertexFormat.FLOATS_PER_VERTEX + 3;
        vertices[b] = n.x();
        vertices[b + 1] = n.y();
        vertices[b + 2] = n.z();
    }

    /** 인덱스 i 번째 값 (삼각형 순회용) */
    public int index(int i) {
        return indices[i];
    }

    public int indexCount() {
        return indexCount;
    }

    /** 다른 메시를 변환해서 붙인다 (재질·색은 그대로) */
    public MeshBuilder append(MeshData mesh, driftengine.math.Mat4 transform) {
        int base = vertexCount();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            if (floatCount + VertexFormat.FLOATS_PER_VERTEX > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            float[] src = mesh.vertexArray();
            int o = i * VertexFormat.FLOATS_PER_VERTEX;
            Vec3 p = transform.transformPoint(new Vec3(src[o], src[o + 1], src[o + 2]));
            Vec3 n = transform.transformDirection(new Vec3(src[o + 3], src[o + 4], src[o + 5])).normalize();
            put(p);
            put(n);
            for (int k = 6; k < VertexFormat.FLOATS_PER_VERTEX; k++) {
                vertices[floatCount++] = src[o + k];
            }
        }
        int[] idx = mesh.indexArray();
        for (int k = 0; k < idx.length; k += 3) {
            triangle(base + idx[k], base + idx[k + 1], base + idx[k + 2]);
        }
        return this;
    }

    public MeshData build() {
        return new MeshData(name, Arrays.copyOf(vertices, floatCount), Arrays.copyOf(indices, indexCount));
    }
}
