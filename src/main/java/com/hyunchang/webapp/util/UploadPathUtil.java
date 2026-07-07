package com.hyunchang.webapp.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 업로드 디렉토리 경로 헬퍼. OS에 따라 NAS 경로(/volume1/...) 또는 로컬 작업 디렉토리(user.dir)를 반환한다.
 *
 * <p>기존에는 5개 클래스(WebConfig, History*, Dating*)가 동일한 분기 로직을 중복 보유했으나 이 유틸로 단일화한다.
 */
public final class UploadPathUtil {

    private static final String NAS_BASE = "/volume1/docker/my-vue-project_backend/uploads";

    private UploadPathUtil() {}

    /** 업로드 베이스 디렉토리 (예: ".../uploads") */
    public static String base() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            return NAS_BASE;
        }
        return System.getProperty("user.dir") + "/uploads";
    }

    /** 이미지 루트 디렉토리. 끝에 슬래시 포함. */
    public static String imagesRoot() {
        return base() + "/images/";
    }

    /** 이미지 하위 디렉토리(예: "history", "dating"). 끝에 슬래시 포함. */
    public static String imagesSubdir(String name) {
        return imagesRoot() + name + "/";
    }

    /** 이미지 하위 디렉토리의 절대 경로(정규화 완료). */
    public static Path imagesSubdirPath(String name) {
        return Paths.get(imagesSubdir(name)).toAbsolutePath().normalize();
    }
}
