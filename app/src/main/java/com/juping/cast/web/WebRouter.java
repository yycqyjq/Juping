package com.juping.cast.web;

import com.juping.cast.dlna.UpnpHttpServer;

import java.io.InputStream;

/**
 * 复合路由（批 3.5）：把多个网页端点串成一条，返回第一个非 {@code null} 的响应。
 *
 * <p><b>为什么需要它</b>：上传功能（{@link WebCastEndpoints}）与安装包功能
 * （{@link ApkEndpoints}）是两条独立的路由，但 {@link UpnpHttpServer} 只接受
 * **一个** {@code WebEndpoints}。用一个路由器把两者串起来，HTTP 层就只改
 * 构造入参一处（原来是 {@code WebCastEndpoints}，换成 {@code WebRouter}），
 * 路由逻辑仍留在 web 包里，HTTP 层不必知道有几个端点。
 *
 * <p>顺序有意义：靠前者优先。上传端点对 {@code /apk*} 一律返回 {@code null}，
 * 所以两条路由的路径空间天然不重叠 —— 顺序只影响「谁先看一眼」。
 */
public final class WebRouter implements UpnpHttpServer.WebEndpoints {

    private final UpnpHttpServer.WebEndpoints[] delegates;

    public WebRouter(UpnpHttpServer.WebEndpoints... delegates) {
        this.delegates = delegates;
    }

    /**
     * @return 第一个非 {@code null} 的响应；都不认则 {@code null}（交回 DLNA 既有逻辑）
     */
    @Override
    public UpnpHttpServer.WebResponse handle(String method, String path, String accept,
                                             String contentType, String range,
                                             int contentLength, InputStream in) {
        for (int i = 0; i < delegates.length; i++) {
            UpnpHttpServer.WebEndpoints d = delegates[i];
            if (d == null) {
                continue;
            }
            UpnpHttpServer.WebResponse r = d.handle(method, path, accept, contentType,
                    range, contentLength, in);
            if (r != null) {
                return r;
            }
        }
        return null;
    }
}
