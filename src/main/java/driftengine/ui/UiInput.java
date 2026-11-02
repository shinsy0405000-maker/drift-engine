package driftengine.ui;

/** 메뉴용 키 (이번 렌더 프레임에 막 눌린 것만) */
public record UiInput(boolean menu, boolean up, boolean down, boolean left, boolean right, boolean confirm, boolean back,
                      boolean enterShop) {

    public static final UiInput NONE = new UiInput(false, false, false, false, false, false, false, false);

    /** 샵 들어가기 키 없이 (메뉴 조작만) */
    public UiInput(boolean menu, boolean up, boolean down, boolean left, boolean right, boolean confirm, boolean back) {
        this(menu, up, down, left, right, confirm, back, false);
    }

    public boolean any() {
        return menu || up || down || left || right || confirm || back;
    }
}
