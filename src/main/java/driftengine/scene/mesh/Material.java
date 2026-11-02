package driftengine.scene.mesh;

/**
 * 표면 재질 (물리 기반 렌더링 값). 정점마다 들어가므로 메시 하나에 여러 재질(도장, 유리, 고무)을 섞을 수 있다.
 *
 * @param metallic  0 = 비금속(플라스틱, 도장 밑칠), 1 = 금속(크롬, 알루미늄)
 * @param roughness 0 = 거울, 1 = 완전 무광
 * @param clearcoat 맨 위 투명 코팅 반사 (자동차 도장 0.8~1)
 * @param emissive  스스로 빛나는 세기 (라이트), 0 = 없음
 * @param surface   절차적 무늬 종류 (SURFACE_*)
 */
public record Material(float metallic, float roughness, float clearcoat, float emissive, float surface) {

    public static final float SURFACE_PLAIN = 0;
    public static final float SURFACE_ASPHALT = 1;
    public static final float SURFACE_GROUND = 2;
    public static final float SURFACE_TIRE = 3;
    /** 투명 유리 (glass.frag: 짙은 썬팅) */
    public static final float SURFACE_GLASS = 4;
    /** 투명 라이트 덮개 (glass.frag: 거의 투명) */
    public static final float SURFACE_LENS = 5;
    /** 카본 능직 무늬 */
    public static final float SURFACE_CARBON = 6;
    /** 도로 (맵): 아스팔트 무늬, 연습장 원형 차선 없음. 정점 색이 밝으면 그 색 페인트(차선·연석) */
    public static final float SURFACE_ROAD = 7;

    public static final Material DEFAULT = new Material(0, 0.6f, 0, 0, SURFACE_PLAIN);
    /** 메탈릭 도장: 금속 입자 + 클리어코트 */
    public static final Material PAINT_METALLIC = new Material(0.55f, 0.35f, 1.0f, 0, SURFACE_PLAIN);
    /** 솔리드 도장 */
    public static final Material PAINT_SOLID = new Material(0.0f, 0.45f, 1.0f, 0, SURFACE_PLAIN);
    /** 무광 도장 (클리어코트 없음) */
    public static final Material PAINT_MATTE = new Material(0.0f, 0.62f, 0.0f, 0, SURFACE_PLAIN);
    /** 크롬 랩 */
    public static final Material PAINT_CHROME = new Material(1.0f, 0.1f, 0.3f, 0, SURFACE_PLAIN);
    public static final Material GLASS = new Material(0.0f, 0.05f, 0.6f, 0, SURFACE_GLASS);
    public static final Material LENS = new Material(0.0f, 0.02f, 0.6f, 0, SURFACE_LENS);
    public static final Material RUBBER = new Material(0.0f, 0.85f, 0, 0, SURFACE_TIRE);
    public static final Material PLASTIC_BLACK = new Material(0.0f, 0.55f, 0, 0, SURFACE_PLAIN);
    public static final Material CARBON = new Material(0.2f, 0.3f, 0.8f, 0, SURFACE_CARBON);
    public static final Material CHROME = new Material(1.0f, 0.12f, 0, 0, SURFACE_PLAIN);
    public static final Material ALUMINIUM = new Material(1.0f, 0.3f, 0, 0, SURFACE_PLAIN);
    public static final Material LIGHT = new Material(0.0f, 0.2f, 0.5f, 6.0f, SURFACE_PLAIN);
    public static final Material TAIL_LIGHT = new Material(0.0f, 0.25f, 0.5f, 3.0f, SURFACE_PLAIN);
    public static final Material ASPHALT = new Material(0.0f, 0.85f, 0, 0, SURFACE_ASPHALT);
    public static final Material GROUND = new Material(0.0f, 0.95f, 0, 0, SURFACE_GROUND);
    public static final Material ROAD = new Material(0.0f, 0.85f, 0, 0, SURFACE_ROAD);
    public static final Material CONCRETE = new Material(0.0f, 0.8f, 0, 0, SURFACE_PLAIN);
    /** 아연 도금 가드레일 */
    public static final Material GALVANIZED = new Material(1.0f, 0.38f, 0, 0, SURFACE_PLAIN);
    public static final Material FOLIAGE = new Material(0.0f, 0.9f, 0, 0, SURFACE_GROUND);
    /** 건물 유리창 (불투명, 반사) */
    public static final Material WINDOW = new Material(0.6f, 0.08f, 0.6f, 0, SURFACE_PLAIN);
    /** 가로등·전광판 빛 */
    public static final Material LAMP = new Material(0.0f, 0.3f, 0, 2.5f, SURFACE_PLAIN);

    public Material {
        if (!(metallic >= 0 && metallic <= 1 && roughness >= 0 && roughness <= 1 && clearcoat >= 0 && clearcoat <= 1
                && emissive >= 0)) {
            throw new IllegalArgumentException("재질 값 범위 오류: " + this);
        }
    }
}
