package com.dsmentoring.serverkeeper.core.channels;

/**
 * 통신 서버 공통 인터페이스
 */
public interface ServerRunner {
    /**
     * 서버 시작
     */
    void start() throws Exception;

    /**
     * 서버 종료
     */
    void stop();
}
