package net.kaaass.zerotierfix.ui;

/**
 * 通知权限的授予状态。
 * <p>
 * 用于记录用户对 {@code POST_NOTIFICATIONS} 权限的授权情况，
 * 避免在已被拒绝后反复弹出系统授权对话框。
 *
 * @author kaaass
 */
public enum NotificationsPermission {
    /**
     * 尚未询问过用户
     */
    NOT_YET_ASKED(0),
    /**
     * 已授予权限
     */
    GRANTED_PERMISSION(1),
    /**
     * 用户已拒绝权限
     */
    DENIED_PERMISSION(2);

    private final int id;

    NotificationsPermission(int id) {
        this.id = id;
    }

    public static NotificationsPermission fromInt(int id) {
        for (NotificationsPermission permission : values()) {
            if (permission.id == id) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unhandled value: " + id);
    }

    public int toInt() {
        return this.id;
    }
}