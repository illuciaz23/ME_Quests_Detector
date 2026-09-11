package io.z23illucia.ae2_ftbquest_detector;

/**
 * 所属队伍在 Jade 与检测器动作栏中的显示方式。
 */
public enum TeamNameDisplayMode {
    /** 队伍名称 + 短 ID，例如 {@code MyTeam#A1B2C3D4} */
    NAME_AND_SHORT_ID,
    /** 仅显示解析出的队伍名称 */
    NAME_ONLY,
    /** 仅显示队伍 UUID 的前 8 位十六进制（大写、无连字符） */
    SHORT_ID_ONLY;

    private static final String TRANSLATION_KEY_PREFIX = "ae2_ftbquest_detector.configuration.teamNameDisplayMode.";

    public String getTranslationKey() {
        return TRANSLATION_KEY_PREFIX + name();
    }
}
