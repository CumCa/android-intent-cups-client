package org.rainbrurpg.transparentcupsclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;

import org.rainbrurpg.transparentcupsclient.app.BasicAuthActivity;
import org.rainbrurpg.transparentcupsclient.ssl.AdditionalKeyManager;
import org.rainbrurpg.transparentcupsclient.ssl.AdditionalKeyStoresSSLSocketFactory;
import org.rainbrurpg.transparentcupsclient.ssl.AndroidCupsHostnameVerifier;

public class HttpConnectionManagement {
    private static final String KEYSTORE_FILE = "cupsprint-trustfile";

    private static final String KEYSTORE_PASSWORD = "i6:[(mW*xh~=Ni;S|?8lz8eZ;!SU(S";

    /**
     * Will handle SSL related stuff to this connection so that certs are properly managed
     *
     * @param connection The target https connection
     */
    public static void handleHttpsUrlConnection(@NonNull HttpsURLConnection connection) {
        connection.setHostnameVerifier(new AndroidCupsHostnameVerifier());
	L.d("Creating handleHttpsUrlConnection...");
	
        try {
	    L.d("Loading key store");
            KeyStore trustStore = loadKeyStore();
            if (trustStore == null) {
		L.e("Failed to load keystore");
                return;
            }

	    L.d("Setting KeyManager");
            KeyManager keyManager = null;
            try {
                keyManager = AdditionalKeyManager.fromAlias();
            } catch (CertificateException e) {
                L.e("Couldn't load system key store: " + e.getLocalizedMessage(), e);
            }

	    L.d("Setting SSLSocketFactory");
            connection.setSSLSocketFactory(new AdditionalKeyStoresSSLSocketFactory(keyManager, trustStore));
        } catch (NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException | KeyManagementException e) {
            L.e("Couldn't handle SSL URL connection: " + e.getLocalizedMessage(), e);
        }
    }

    /**
     * Try to get the contents of the local key store
     *
     * @return A valid KeyStore object if nothing went wrong, null otherwise
     */
    @Nullable
    private static KeyStore loadKeyStore() {
        KeyStore trustStore;
        try {
	    L.d("Opening local key store");
            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (KeyStoreException e) {
            L.e("Couldn't open local key store", e);
            return null;
        }

        // Load the local keystore into memory
        try {
	    L.d("Loading local key store '" + KEYSTORE_FILE + "' into memory");
            FileInputStream fis = CupsPrintApp.getContext().openFileInput(KEYSTORE_FILE);
            trustStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
            return trustStore;
        } catch (FileNotFoundException e) {
            // This one can be ignored safely - at least not sent to crashlytics
            L.e("Couldn't open local key store: " + e.getLocalizedMessage());
        } catch (IOException | NoSuchAlgorithmException | CertificateException e) {
            L.e("Couldn't open local key store", e);
        }
	catch(Exception e) {
            L.e("Can't load key store into memory ", e);
	}

        // if we couldn't load local keystore file, create an new empty one
        try {
	    L.d("Creating a new keystore");
            trustStore.load(null, null);
        } catch (IOException|NoSuchAlgorithmException|CertificateException e) {
            L.e("Couldn't create new key store", e);
        }
	catch(Exception e) {
            L.e("Can't load trust store!", e);
	}

        return trustStore;
    }

    /**
     * Add certs to the keystore (thus trusting them)
     *
     * @param chain The chain of certs to trust
     * @return true if it was saved, false otherwise
     */
    public static boolean saveCertificates(X509Certificate[] chain) {
        // Load existing certs
        KeyStore trustStore = loadKeyStore();
        if (trustStore == null) {
            return false;
        }

        // Add new certs
        try {
            for (final X509Certificate c : chain) {
                trustStore.setCertificateEntry(c.getSubjectDN().toString(), c);
            }
        } catch (final KeyStoreException e) {
            L.e("Couldn't store cert chain into key store", e);
            return false;
        }

        // Save new keystore
        FileOutputStream fos = null;
        try {
            fos = CupsPrintApp.getContext().openFileOutput(KEYSTORE_FILE, Context.MODE_PRIVATE);
            trustStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
            fos.close();
        } catch (final Exception e) {
            L.e("Unable to save key store", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    L.e("Couldn't close key store", e);
                }
            }
        }

        return true;
    }

    /**
     * See if there are some basic auth credentials saved, and configure the connection
     *
     * @param url        URL we're about to request
     * @param connection The connection to be configured
     */
    public static void handleBasicAuth(URL url, HttpURLConnection connection) {
        SharedPreferences prefs = CupsPrintApp.getContext().getSharedPreferences(BasicAuthActivity.CREDENTIALS_FILE, Context.MODE_PRIVATE);

        int id = BasicAuthActivity.findSavedCredentialsId(url.toString(), prefs);
        if (id < 0) {
            return;
        }

        String username = prefs.getString(BasicAuthActivity.KEY_BASIC_AUTH_LOGIN + id, "");
        String password = prefs.getString(BasicAuthActivity.KEY_BASIC_AUTH_PASSWORD + id, "");
        try {
            String encoded = Base64.encodeToString((username + ":" + password).getBytes("UTF-8"), Base64.NO_WRAP);
            connection.setRequestProperty("Authorization", "Basic " + encoded);
        } catch (UnsupportedEncodingException e) {
            L.e("Couldn't base64 encode basic auth credentials", e);
        }
    }
}
