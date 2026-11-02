package driftengine.vehicle;

/** 구동 방식 */
public enum DriveLayout {
    RWD, FWD, AWD;

    public boolean drivesFront() {
        return this != RWD;
    }

    public boolean drivesRear() {
        return this != FWD;
    }
}
