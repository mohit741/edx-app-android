package org.edx.mobile.view;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.ProgressBar;

import com.google.inject.Inject;

import org.edx.mobile.R;
import org.edx.mobile.authentication.LoginService;
import org.edx.mobile.core.IEdxEnvironment;
import org.edx.mobile.event.SessionIdRefreshEvent;
import org.edx.mobile.http.HttpStatus;
import org.edx.mobile.http.HttpStatusException;
import org.edx.mobile.http.notifications.FullScreenErrorNotification;
import org.edx.mobile.http.provider.OkHttpClientProvider;
import org.edx.mobile.interfaces.RefreshListener;
import org.edx.mobile.interfaces.WebViewStatusListener;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.services.EdxCookieManager;
import org.edx.mobile.util.Config;
import org.edx.mobile.util.WebViewUtil;
import org.edx.mobile.view.custom.EdxWebView;
import org.edx.mobile.view.custom.URLInterceptorWebViewClient;

import java.io.InputStream;

import de.greenrobot.event.EventBus;
import okhttp3.Cookie;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * An abstract fragment providing basic functionality for URL interception, its follow up action,
 * error handling and show page progress based on page status.
 */
public abstract class BaseWebViewFragment extends OfflineSupportBaseFragment
        implements WebViewStatusListener, RefreshListener {
    protected final Logger logger = new Logger(getClass().getName());

    private EdxWebView webView;
    protected ProgressBar progressWheel;

    protected FullScreenErrorNotification errorNotification;

    @Inject
    protected IEdxEnvironment environment;

    @Inject
    private OkHttpClientProvider okHttpClientProvider;


    @Inject
    private LoginService loginService;
    @Inject
    private Config config;


    private Call<RequestBody> loginCall;

    protected URLInterceptorWebViewClient client;

    public abstract FullScreenErrorNotification initFullScreenErrorNotification();

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        errorNotification = initFullScreenErrorNotification();
        webView = (EdxWebView) view.findViewById(R.id.webview);
        progressWheel = (ProgressBar) view.findViewById(R.id.loading_indicator);

        initWebView();
    }

    @Override
    public void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }

    private void initWebView() {
        client = new URLInterceptorWebViewClient(getActivity(), webView);

        // if all the links are to be treated as external
        client.setAllLinksAsExternal(isAllLinksExternal());

        client.setPageStatusListener(pageStatusListener);
        webView.getSettings().setJavaScriptEnabled(true);
    }

    /**
     * Loads the given URL into {@link #webView}.
     *
     * @param url The URL to load.
     */
    protected void loadUrl(@NonNull String url) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            loadUrl(url,true);
        else {
            final EdxCookieManager edxCookieManager = EdxCookieManager.getSharedInstance(getContext());
            if(edxCookieManager.isSessionCookieMissingOrExpired())
                setSessionCookieAndLoadUrl(url, edxCookieManager);
        }
    }

    protected void loadUrl(@NonNull String url, Boolean t) {
        WebViewUtil.loadUrlBasedOnOsVersion(getContext(), webView, url, this, errorNotification,
                okHttpClientProvider, R.string.lbl_reload, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onRefresh();
                    }
                });
    }

    @Override
    public void showLoadingProgress() {
        if (progressWheel != null) {
            progressWheel.setVisibility(View.VISIBLE);
        }
        if (webView != null) {
            webView.setVisibility(View.GONE);
        }
    }

    @Override
    public void hideLoadingProgress() {
        if (progressWheel != null) {
            progressWheel.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void clearWebView() {
        WebViewUtil.clearWebviewHtml(webView);
    }

    /**
     * By default, all links will not be treated as external.
     * Depends on host, as long as the links have same host, they are treated as non-external links.
     *
     * @return
     */
    protected boolean isAllLinksExternal() {
        return false;
    }

    /**
     * See description of: {@link org.edx.mobile.view.custom.URLInterceptorWebViewClient.IPageStatusListener#onPageLoadProgressChanged(WebView, int)
     * IPageStatusListener#onPageLoadProgressChanged}.
     */
    protected void onWebViewLoadProgressChanged(int progress) {
    }

    /*
     * In order to avoid reflection issues of public functions in event bus especially those that
     * aren't available on a certain api level, this listener has been refactored to a class
     * variable which is better explained in following references:
     * https://github.com/greenrobot/EventBus/issues/149
     * http://greenrobot.org/eventbus/documentation/faq/
     */
    private URLInterceptorWebViewClient.IPageStatusListener pageStatusListener = new URLInterceptorWebViewClient.IPageStatusListener() {
        @Override
        public void onPageStarted() {
            showLoadingProgress();
        }

        @Override
        public void onPageFinished() {
            injectCSS();
            hideLoadingProgress();
        }

        @Override
        public void onPageLoadError(WebView view, int errorCode, String description,
                                    String failingUrl) {
            errorNotification.showError(getContext(),
                    new HttpStatusException(Response.error(HttpStatus.SERVICE_UNAVAILABLE,
                            ResponseBody.create(MediaType.parse("text/plain"), description))),
                    R.string.lbl_reload, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            onRefresh();
                        }
                    });
            clearWebView();
        }

        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onPageLoadError(WebView view, WebResourceRequest request,
                                    WebResourceResponse errorResponse,
                                    boolean isMainRequestFailure) {
            if (isMainRequestFailure) {
                errorNotification.showError(getContext(),
                        new HttpStatusException(Response.error(errorResponse.getStatusCode(),
                                ResponseBody.create(MediaType.parse(errorResponse.getMimeType()),
                                        errorResponse.getReasonPhrase()))),
                        R.string.lbl_reload, new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                onRefresh();
                            }
                        });
                clearWebView();
            }
        }

        @Override
        public void onPageLoadProgressChanged(WebView view, int progress) {
            onWebViewLoadProgressChanged(progress);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized  void setSessionCookieAndLoadUrl(@NonNull String url, EdxCookieManager edxCookieManager){
        if (loginCall == null || loginCall.isCanceled()) {
            loginCall = loginService.login();
            loginCall.enqueue(new Callback<RequestBody>() {
                @Override
                public void onResponse(@NonNull final Call<RequestBody> call,
                                       @NonNull final Response<RequestBody> response) {
                    edxCookieManager.clearWebWiewCookie();
                    final CookieManager cookieManager = CookieManager.getInstance();
                    for (Cookie cookie : Cookie.parseAll(
                            call.request().url(), response.headers())) {
                        cookieManager.setCookie(config.getApiHostURL(), cookie.toString(),  new ValueCallback<Boolean>() {
                            @Override
                            public void onReceiveValue(Boolean t) {
                                if(t)
                                    edxCookieManager.setAuthSessionCookieExpiration();
                                loadUrl(url,t);
                            }
                        });
                    }
                    EventBus.getDefault().post(new SessionIdRefreshEvent(true));
                    loginCall = null;
                }

                @Override
                public void onFailure(@NonNull final Call<RequestBody> call,
                                      @NonNull final Throwable error) {
                    EventBus.getDefault().post(new SessionIdRefreshEvent(false));
                    loginCall = null;
                }
            });
        }
    }
    private void injectCSS() {
        try {
            InputStream inputStream = getContext().getAssets().open("css/style.css");
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            String encoded = Base64.encodeToString(buffer, Base64.NO_WRAP);
            webView.loadUrl("javascript:(function() {" +
                    "var parent = document.getElementsByTagName('head').item(0);" +
                    "var style = document.createElement('style');" +
                    "style.type = 'text/css';" +
                    // Tell the browser to BASE64-decode the string into your script !!!
                    "style.innerHTML = window.atob('" + encoded + "');" +
                    "parent.appendChild(style)" +
                    "})()");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public EdxWebView getWebView(){
        return webView;
    }
}
