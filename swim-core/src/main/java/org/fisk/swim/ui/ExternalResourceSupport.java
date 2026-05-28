package org.fisk.swim.ui;

import java.net.URI;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.launcher.DesktopSupport;

final class ExternalResourceSupport {
    interface UrlOpener {
        boolean open(String url);
    }

    interface TextCopier {
        boolean copy(String text);
    }

    private static volatile UrlOpener _urlOpener = ExternalResourceSupport::openUrlWithSystem;
    private static volatile TextCopier _textCopier = ExternalResourceSupport::copyTextWithSystem;

    private ExternalResourceSupport() {
    }

    static boolean openUrl(String url) {
        return _urlOpener.open(url);
    }

    static boolean copyText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Copy.getInstance().setText(text, false);
        return _textCopier.copy(text);
    }

    private static boolean openUrlWithSystem(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            return DesktopSupport.openUri(URI.create(url));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean copyTextWithSystem(String text) {
        return DesktopSupport.copyText(text);
    }

    static void setUrlOpenerForTesting(UrlOpener urlOpener) {
        _urlOpener = urlOpener == null ? ExternalResourceSupport::openUrlWithSystem : urlOpener;
    }

    static void setTextCopierForTesting(TextCopier textCopier) {
        _textCopier = textCopier == null ? ExternalResourceSupport::copyTextWithSystem : textCopier;
    }

    static void resetForTesting() {
        _urlOpener = ExternalResourceSupport::openUrlWithSystem;
        _textCopier = ExternalResourceSupport::copyTextWithSystem;
    }
}
