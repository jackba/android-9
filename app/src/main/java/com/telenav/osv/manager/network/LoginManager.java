package com.telenav.osv.manager.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import javax.inject.Inject;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentTransaction;
import android.webkit.CookieManager;
import com.android.volley.DefaultRetryPolicy;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.LoginEvent;
import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginResult;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.telenav.osv.R;
import com.telenav.osv.activity.OSVActivity;
import com.telenav.osv.data.AccountPreferences;
import com.telenav.osv.data.Preferences;
import com.telenav.osv.event.EventBus;
import com.telenav.osv.http.AuthRequest;
import com.telenav.osv.item.AccountData;
import com.telenav.osv.item.network.ApiResponse;
import com.telenav.osv.item.network.AuthData;
import com.telenav.osv.item.network.OsmProfileData;
import com.telenav.osv.listener.network.NetworkResponseDataListener;
import com.telenav.osv.listener.network.OsmAuthDataListener;
import com.telenav.osv.listener.network.OsvRequestResponseListener;
import com.telenav.osv.manager.network.parser.AuthDataParser;
import com.telenav.osv.manager.network.parser.OsmProfileDataParser;
import com.telenav.osv.ui.fragment.OAuthDialogFragment;
import com.telenav.osv.ui.fragment.ProfileFragment;
import com.telenav.osv.utils.BackgroundThreadPool;
import com.telenav.osv.utils.Log;
import com.telenav.osv.utils.Utils;
import io.fabric.sdk.android.Fabric;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthProvider;
import static com.telenav.osv.data.Preferences.URL_ENV;
import static com.telenav.osv.item.network.UserData.TYPE_UNKNOWN;

/**
 * component responsible for logging in using several accounts
 * Created by Kalman on 28/02/2017.
 */
public class LoginManager extends NetworkManager implements GoogleApiClient.OnConnectionFailedListener {

    public static final String LOGIN_TYPE_OSM = "osm";

    public static final String LOGIN_TYPE_GOOGLE = "google";

    public static final String LOGIN_TYPE_FACEBOOK = "facebook";

    private static final String TAG = "LoginManager";

    private static final int REQUEST_CODE_LOGIN_GOOGLE = 10001;

    /**
     * request url for login details of OSM user
     */
    private static final String URL_USER_DETAILS = "http://api.openstreetmap.org/api/0.6/user/details";

    private static String URL_AUTH_OSM = "http://" + "&&" + "auth/openstreetmap/client_auth";

    private static String URL_AUTH_GOOGLE = "http://" + "&&" + "auth/google/client_auth";

    private static String URL_AUTH_FACEBOOK = "http://" + "&&" + "auth/facebook/client_auth";

    private final Context mContext;

    private final GoogleApiClient mGoogleApiClient;

    private final CallbackManager mFacebookCallbackManager;

    private Preferences prefs;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private FirebaseAuth mAuth;

    private AuthDataParser mAuthDataParser = new AuthDataParser();

    @Inject
    public LoginManager(Context context, Preferences preferences, AccountPreferences accountPreferences) {
        super(context, accountPreferences);
        this.prefs = preferences;
        this.mContext = context;
        mAuth = FirebaseAuth.getInstance();
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile()
                .requestIdToken(context.getString(R.string.google_client_id))
                .requestScopes(new Scope(Scopes.PROFILE), new Scope("https://www.googleapis.com/auth/contacts.readonly")).build();
        mGoogleApiClient = new GoogleApiClient.Builder(context).addApi(Auth.GOOGLE_SIGN_IN_API, gso).build();
        mGoogleApiClient.registerConnectionFailedListener(this);
        mGoogleApiClient.connect();
        FirebaseAuth.AuthStateListener mAuthListener = firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {//user signed in
                Log.d(TAG, "onAuthStateChanged: user is from " + user.getProviderId());
                String imgLink = null;
                Uri url = user.getPhotoUrl();
                if (url != null) {
                    imgLink = url.toString();
                }
                final String finalImgLink = imgLink;
                appPrefs.setUserPhotoUrl(finalImgLink);
                final String id = appPrefs.getUserId();
                final String userName = appPrefs.getUserName();
                final String displayName = appPrefs.getUserDisplayName();
                final int type = appPrefs.getUserType();
                final int loginType = appPrefs.getLoginType();
                mHandler.post(() -> {
                    if (!appPrefs.isLoggedIn()) {
                        logout();
                    } else {
                        onLoggedIn(new AccountData(id, userName, displayName, finalImgLink, type, loginType));
                    }
                });
            } else {//user signed out
                Log.d(TAG, "onAuthStateChanged:signed_out");
            }
        };
        mAuth.addAuthStateListener(mAuthListener);

        mFacebookCallbackManager = CallbackManager.Factory.create();

        com.facebook.login.LoginManager.getInstance().registerCallback(mFacebookCallbackManager, new FacebookCallback<LoginResult>() {

            @Override
            public void onSuccess(LoginResult loginResult) {
                Log.d(TAG, "mFacebookCallbackManager onSuccess: ");
                handleFacebookAccessToken(loginResult.getAccessToken());
            }

            @Override
            public void onCancel() {
                Log.d(TAG, "mFacebookCallbackManager onCancel: ");
            }

            @Override
            public void onError(FacebookException exception) {
                Log.d(TAG, "mFacebookCallbackManager onError: " + Log.getStackTraceString(exception));
            }
        });
        EventBus.register(this);
    }

    @Override
    protected void setupUrls() {
        URL_AUTH_OSM = URL_AUTH_OSM.replace("&&", URL_ENV[mCurrentServer]);
        URL_AUTH_FACEBOOK = URL_AUTH_FACEBOOK.replace("&&", URL_ENV[mCurrentServer]);
        URL_AUTH_GOOGLE = URL_AUTH_GOOGLE.replace("&&", URL_ENV[mCurrentServer]);
    }

    @Override
    public void destroy() {
        super.destroy();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(TAG, "onConnectionFailed: error connecting google api: " + connectionResult.getErrorMessage());
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + " resultCode=" + resultCode);
        if (requestCode == LoginManager.REQUEST_CODE_LOGIN_GOOGLE) {
            Log.d(TAG, "onActivityResult: REQUEST_CODE_LOGIN_GOOGLE");
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "onActivityResult: result OK");
                // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
                GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
                if (result.isSuccess()) {
                    Log.d(TAG, "onActivityResult: Google Sign In was successful, authenticate with Firebase");
                    handleGoogleLoginResult(result);
                } else {
                    Log.d(TAG, "onActivityResult: Google Sign In was unsuccessfull, rollback login");
                    logout();
                }
            }
        } else {
            Log.d(TAG, "onActivityResult: not google request code, forwarding to Facebook callback manager " + mFacebookCallbackManager);
            mFacebookCallbackManager.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void login(OSVActivity activity, String type) {
        Log.d(TAG, "login: requested login type " + type);
        switch (type) {
            case LOGIN_TYPE_FACEBOOK:
                loginFacebook(activity);
                break;
            case LOGIN_TYPE_GOOGLE:
                loginGoogle(activity);
                break;
            case LOGIN_TYPE_OSM:
                showOSMLoginScreen(activity);
                break;
        }
    }

    public void logout() {
        appPrefs.setUserId("");
        appPrefs.setLoginType(AccountData.ACCOUNT_TYPE_NONE);
        appPrefs.setUserName("");
        appPrefs.setUserDisplayName("");
        appPrefs.setUserPhotoUrl("");
        appPrefs.setUserType(TYPE_UNKNOWN);
        appPrefs.saveAuthToken("");
        logoutFirebase();
        SharedPreferences profilePrefs = mContext.getSharedPreferences(ProfileFragment.PREFS_NAME, Context.MODE_PRIVATE);
        profilePrefs.edit().clear().apply();
        if (!Utils.DEBUG || !prefs.getSaveAuth()) {
            final SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(mContext).edit();
            editor.clear();
            editor.apply();
            CookieManager.getInstance().removeAllCookies(value -> Log.d(TAG, "logout: removed cookies = " + value));
        }
        if (Fabric.isInitialized()) {
            Crashlytics.setString(Log.USER_TYPE, "");
        }
    }

    private void showOSMLoginScreen(final OSVActivity activity) {
        if (Utils.isInternetAvailable(activity)) {
            activity.enableProgressBar(true);
            showOSMLoginDialog(activity, new OsmAuthDataListener() {

                String mProfilePictureUrl;

                @Override
                public void requestFailed(int status, AuthData details) {
                    activity.enableProgressBar(false);
                }

                @Override
                public void requestFinished(int status, OsmProfileData osmProfileData) {
                    if (osmProfileData != null) {
                        appPrefs.setUserPhotoUrl(osmProfileData.getProfilePictureUrl());
                        mProfilePictureUrl = osmProfileData.getProfilePictureUrl();
                    }
                    activity.enableProgressBar(false);
                }

                @Override
                public void requestFinished(int status, AuthData userData) {

                    final String id = userData.getId();
                    final String displayName = userData.getDisplayName();
                    final String token = userData.getAccessToken();
                    final String userName = userData.getUsername();
                    final int type = userData.getUserType();
                    final int loginType = userData.getLoginType();
                    final String finalImgLink = mProfilePictureUrl;
                    activity.runOnUiThread(() -> {

                        if ("".equals(userName) || "".equals(token)) {
                            logout();
                        } else {
                            onLoggedIn(new AccountData(id, userName, displayName, finalImgLink, type, loginType));
                        }
                        activity.enableProgressBar(false);
                        activity.finish();
                    });
                }
            }, () -> activity.enableProgressBar(false));
        } else {
            activity.showSnackBar(R.string.no_internet_connection_label, Snackbar.LENGTH_SHORT);
        }
    }

    private void loginFacebook(OSVActivity activity) {
        if (mAuth.getCurrentUser() != null) {
            Log.d(TAG, "loginFacebook: firebase already has a logged user, from " + mAuth.getCurrentUser().getProviderId());
            logoutFirebase();
        }
        Log.d(TAG, "loginFacebook: sending request");
        com.facebook.login.LoginManager.getInstance().logInWithReadPermissions(activity, Arrays.asList("public_profile", "email"));
    }

    private void loginGoogle(OSVActivity activity) {
        if (mAuth.getCurrentUser() != null) {
            Log.d(TAG, "loginGoogle: firebase already has a logged user, from " + mAuth.getCurrentUser().getProviderId());
            logoutFirebase();
        }
        Log.d(TAG, "loginGoogle: sending intent");
        Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
        activity.startActivityForResult(signInIntent, REQUEST_CODE_LOGIN_GOOGLE);
    }

    /**
     * displays a webview with the osm login site.
     * @param activity the ref for the support fragmenbt manager
     * @param listener response listener
     * @param onDetachListener on detach listener for the webview
     */
    private void showOSMLoginDialog(final FragmentActivity activity, final OsmAuthDataListener listener,
                                    final OAuthDialogFragment.OnDetachListener onDetachListener) {
        runInBackground(() -> {
            try {
                final CommonsHttpOAuthConsumer consumer;
                final CommonsHttpOAuthProvider provider;
                consumer =
                        new CommonsHttpOAuthConsumer(activity.getString(R.string.osm_consumer_key),
                                activity.getString(R.string.osm_consumer_secret_key));

                provider = new CommonsHttpOAuthProvider("https://www.openstreetmap.org/oauth/request_token",
                        "https://www.openstreetmap.org/oauth/access_token",
                        "https://www.openstreetmap.org/oauth/authorize");
                provider.setOAuth10a(true);
                String authUrl = provider.retrieveRequestToken(consumer, "osmlogin://telenav?");
                OAuthDialogFragment newFragment = new OAuthDialogFragment();
                newFragment.setOnDetachListener(onDetachListener);
                newFragment.setURL(authUrl);
                newFragment.setResultListener(url -> runInBackground(() -> {
                    try {
                        Uri uri = Uri.parse(url);
                        String oauthVerifier = uri.getQueryParameter("oauth_verifier");
                        provider.retrieveAccessToken(consumer, oauthVerifier);
                        String requestToken = consumer.getToken();
                        String secretToken = consumer.getTokenSecret();

                        //get user details like username and profile picture url
                        HttpGet request = new HttpGet(URL_USER_DETAILS);
                        consumer.sign(request);
                        HttpClient httpclient = new DefaultHttpClient();
                        HttpResponse httpResponse;
                        final String response;
                        httpResponse = httpclient.execute(request);
                        StatusLine statusLine = httpResponse.getStatusLine();
                        if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            httpResponse.getEntity().writeTo(out);
                            response = out.toString();
                            out.close();
                        } else {
                            //Closes the connection.
                            httpResponse.getEntity().getContent().close();
                            throw new IOException(statusLine.getReasonPhrase());
                        }
                        listener.requestFinished(statusLine.getStatusCode(), new OsmProfileDataParser().parse(response));
                        authenticate(URL_AUTH_OSM, requestToken, secretToken, listener);
                    } catch (Exception e) {
                        Log.d(TAG, Log.getStackTraceString(e));
                        ApiResponse response = new ApiResponse();
                        response.setHttpCode(403);
                        response.setHttpMessage(e.getMessage());
                        listener.requestFailed(NetworkResponseDataListener.HTTP_BAD_REQUEST, new AuthData());
                    }
                }));
                FragmentTransaction ft = activity.getSupportFragmentManager().beginTransaction();
                ft.addToBackStack("OAuthDialogFragment.TAG");
                newFragment.show(ft, OAuthDialogFragment.TAG);
            } catch (Exception e) {
                Log.e(TAG, "showOSMLoginDialog: logging in failed " + e.toString());
                ApiResponse response = new ApiResponse();
                response.setHttpCode(403);
                response.setHttpMessage(e.getMessage());
                listener.requestFailed(NetworkResponseDataListener.HTTP_BAD_REQUEST, new AuthData());
            }
        });
    }

    /**
     * lists the details of the images in an online sequence
     * @param listener request listener
     */
    private void authenticate(final String url, final String requestToken, final String secretToken,
                              final NetworkResponseDataListener<AuthData> listener) {
        Log.d(TAG, "authenticate: " + url);
        AuthRequest request = new AuthRequest(url, new OsvRequestResponseListener<AuthDataParser, AuthData>(mAuthDataParser) {

            @Override
            public void onSuccess(final int status, final AuthData authData) {
                runInBackground(() -> {
                    int loginType = AccountData.ACCOUNT_TYPE_OSM;
                    if (url.contains("facebook")) {
                        loginType = AccountData.ACCOUNT_TYPE_FACEBOOK;
                    } else if (url.contains("google")) {
                        loginType = AccountData.ACCOUNT_TYPE_GOOGLE;
                    }
                    authData.setLoginType(loginType);
                    appPrefs.setUserId(authData.getId());
                    appPrefs.setUserName(authData.getUsername());
                    appPrefs.setUserDisplayName(authData.getDisplayName());
                    appPrefs.setUserType(authData.getUserType());
                    appPrefs.setLoginType(authData.getLoginType());
                    appPrefs.saveAuthToken(authData.getAccessToken());

                    listener.requestFinished(status, authData);
                    Log.d(TAG, "authenticate: success");
                });
            }

            @Override
            public void onFailure(int status, AuthData authData) {
                listener.requestFailed(status, authData);
            }
        }, requestToken, secretToken);
        request.setRetryPolicy(new DefaultRetryPolicy(10000, 3, 1f));
        mQueue.add(request);
    }

    private void handleGoogleLoginResult(final GoogleSignInResult result) {
        BackgroundThreadPool.post(() -> {
            try {
                Log.d(TAG, "handleSignInResult: success = " + result.isSuccess());
                GoogleSignInAccount acct = result.getSignInAccount();
                if (acct == null) {
                    Log.d(TAG, "handleGoogleLoginResult: GoogleSignInAccount is null, rolling back login");
                    logout();
                    return;
                }
                final AuthCredential credential = GoogleAuthProvider.getCredential(acct.getIdToken(), null);
                GoogleAccountCredential credential2 = GoogleAccountCredential.usingOAuth2(mContext, Collections.singleton(Scopes.PROFILE));
                credential2.setSelectedAccount(acct.getAccount());
                String token = null;
                try {
                    token = credential2.getToken();
                } catch (IOException | GoogleAuthException e) {
                    Log.d(TAG, "handleGoogleLoginResult: " + Log.getStackTraceString(e));
                }
                authenticate(URL_AUTH_GOOGLE, token, null, new NetworkResponseDataListener<AuthData>() {

                    @Override
                    public void requestFailed(int status, AuthData details) {
                        Log.d(TAG, "handleGoogleLoginResult requestFinished: rolling back login");
                        logout();
                    }

                    @Override
                    public void requestFinished(int status, AuthData userData) {
                        Log.d(TAG, "handleGoogleLoginResult requestFinished: API token received, signing in with firebase as well");
                        mAuth.signInWithCredential(credential).addOnCompleteListener(task -> {
                            Log.d(TAG, "signInWithCredential:onComplete:" + task.isSuccessful());
                            if (!task.isSuccessful()) {
                                logout();
                            }
                        }).addOnFailureListener(e -> {
                            Log.d(TAG, "signInWithCredential:onFailure:" + Log.getStackTraceString(e));
                            logout();
                        });
                    }
                });
            } catch (Exception e) {
                Log.d(TAG, "handleGoogleLoginResult: " + Log.getStackTraceString(e));
                Log.d(TAG, "handleGoogleLoginResult: rolling back login");
                logout();
            }
        });
    }

    private void handleFacebookAccessToken(final AccessToken token) {
        Log.d(TAG, "handleFacebookAccessToken:" + token);

        final AuthCredential credential = FacebookAuthProvider.getCredential(token.getToken());
        authenticate(URL_AUTH_FACEBOOK, token.getToken(), "", new NetworkResponseDataListener<AuthData>() {

            @Override
            public void requestFailed(int status, AuthData details) {
                Log.d(TAG, "handleFacebookAccessToken requestFinished: API call failed, rolling back login");
                logout();
            }

            @Override
            public void requestFinished(int status, AuthData userData) {
                Log.d(TAG, "handleFacebookAccessToken requestFinished: API token received, signing in with firebase as well");
                mAuth.signInWithCredential(credential).addOnCompleteListener(task -> {
                    Log.d(TAG, "handleFacebookAccessToken signInWithCredential:onComplete:" + task.isSuccessful());

                    if (!task.isSuccessful()) {
                        Log.d(TAG, "handleFacebookAccessTokenonComplete: unsuccessful, rolling back login");
                        logout();
                    }
                });
            }
        });
    }

    private void logoutFirebase() {
        if (mAuth.getCurrentUser() != null) {
            Log.d(TAG, "logoutFirebase: " + mAuth.getCurrentUser().getProviderId());
            //signed in with facebook or google
            FirebaseAuth.getInstance().signOut();
            // user is now signed out
            signOutGoogle();
            signOutFacebook();
        }
    }

    private void signOutGoogle() {
        Log.d(TAG, "signOutGoogle: ");
        try {
            Auth.GoogleSignInApi.signOut(mGoogleApiClient);
        } catch (Exception e) {
            Log.d(TAG, "signOutGoogle: " + Log.getStackTraceString(e));
        }
    }

    private void signOutFacebook() {
        Log.d(TAG, "signOutFacebook: ");
        try {
            com.facebook.login.LoginManager.getInstance().logOut();
        } catch (Exception e) {
            Log.d(TAG, "signOutFacebook: " + Log.getStackTraceString(e));
        }
    }

    private void onLoggedIn(AccountData accountData) {
        if (accountData.isDriver()) {
            prefs.setGamificationEnabled(false);
        }
        if (Fabric.isInitialized()) {
            int loginType = appPrefs.getLoginType();
            int type = appPrefs.getUserType();
            Answers.getInstance().logLogin(new LoginEvent().putSuccess(true).putMethod("" + loginType).putCustomAttribute("userType", type));
            Crashlytics.setInt(Log.USER_TYPE, type);
        }
    }
}
