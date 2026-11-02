package driftengine.vehicle.visual;

/**
 * 바디킷 부품 선택 (차고에서 바꾸는 부분). 숫자가 클수록 과격한 부품.
 *
 * @param front    앞범퍼: 0 순정, 1 립 스포일러, 2 에어로(립 + 카나드)
 * @param side     사이드 스커트: 0 없음, 1 있음
 * @param rear     뒤: 0 순정, 1 디퓨저
 * @param spoiler  스포일러: 0 없음, 1 덕테일, 2 GT 윙
 * @param widebody 0 순정 펜더, 1 와이드 펜더 (오버펜더)
 */
public record BodyKit(int front, int side, int rear, int spoiler, int widebody) {

    /** 부품별 최대값 (STOCK 보다 먼저 초기화되어야 함) */
    public static final int[] MAX = {2, 1, 1, 2, 1};
    public static final BodyKit STOCK = new BodyKit(0, 0, 0, 0, 0);

    public BodyKit {
        int[] v = {front, side, rear, spoiler, widebody};
        for (int i = 0; i < v.length; i++) {
            if (v[i] < 0 || v[i] > MAX[i]) {
                throw new IllegalArgumentException("바디킷 값 범위 오류: " + this);
            }
        }
    }

    /** "1,0,1,2,0" 형식 */
    public static BodyKit parse(String text) {
        String[] p = text.trim().split(",");
        if (p.length != 5) {
            throw new IllegalArgumentException("바디킷은 숫자 5개 (앞,옆,뒤,스포일러,와이드): " + text);
        }
        int[] v = new int[5];
        for (int i = 0; i < 5; i++) {
            v[i] = Integer.parseInt(p[i].trim());
        }
        return new BodyKit(v[0], v[1], v[2], v[3], v[4]);
    }

    public String text() {
        return front + "," + side + "," + rear + "," + spoiler + "," + widebody;
    }
}
