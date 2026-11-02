package driftengine.ui;

/** 화면 위에 그리는 것 (계기판, 메뉴). 렌더 프레임마다 canvas 에 그린다 */
@FunctionalInterface
public interface Overlay {
    void draw(UiCanvas canvas);
}
