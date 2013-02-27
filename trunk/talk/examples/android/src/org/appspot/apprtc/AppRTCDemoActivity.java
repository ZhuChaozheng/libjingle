/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.appspot.apprtc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.LocalMediaStream;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.LinkedList;
import java.util.List;

/**
 * Main Activity of the AppRTCDemo Android app demonstrating interoperability
 * between the Android/Java implementation of PeerConnection and the
 * apprtc.appspot.com demo webapp.
 */
public class AppRTCDemoActivity extends Activity
    implements AppRTCClient.IceServersObserver {
  private static final String TAG = "AppRTCDemoActivity";
  private PeerConnection pc;
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final GAEChannelClient.MessageHandler gaeHandler = new GAEHandler();
  private final AppRTCClient appRtcClient =
      new AppRTCClient(this, gaeHandler, this);
  private VideoStreamsView vsv;
  private Toast logToast;
  private LinkedList<IceCandidate> queuedRemoteCandidates =
      new LinkedList<IceCandidate>();

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Point displaySize = new Point();
    getWindowManager().getDefaultDisplay().getSize(displaySize);
    vsv = new VideoStreamsView(this, displaySize);
    setContentView(vsv);

    abortUnless(PeerConnectionFactory.initializeAndroidGlobals(this),
        "Failed to initializeAndroidGlobals");
    // Sadly, the following dance is required because Android's Camera interface
    // won't hand over captured byte buffers without *also* rendering to a
    // surface.  Crazy!
    SurfaceView previewSurfaceView =
        org.webrtc.videoengine.ViERenderer.CreateLocalRenderer(this);
    abortUnless(previewSurfaceView != null,
        "Failed to create a (dummy) local renderer");
    addContentView(previewSurfaceView, new ViewGroup.LayoutParams(16, 16));

    // TODO(fischman): allow this client to act as a room-creator, handing out
    // the new room URL and acting as a JSEP "answerer".  ATM this only acts as
    // an offerer and on already-existing rooms with a single present user.
    // TODO(fischman): also, support &debug=loopback
    final Intent intent = getIntent();
    if (!intent.getAction().equals("android.intent.action.VIEW")) {
      logAndToast("AppRTC must be launched via an intent opening a room URL " +
          "such as https://apprtc.appspot.com/?r=...  Exiting.");
      disconnectAndExit();
      return;
    }
    appRtcClient.connectToRoom(intent.getData().toString());
    logAndToast("Connecting to room...");
  }

  @Override
  public void onPause() {
    super.onPause();
    vsv.onPause();
    // TODO(fischman): IWBN to support pause/resume, but the WebRTC codebase
    // isn't ready for that yet; e.g.
    // https://code.google.com/p/webrtc/issues/detail?id=1407
    // Instead, simply exit instead of pausing (the alternative leads to
    // system-borking with wedged cameras; e.g. b/8224551)
    disconnectAndExit();
  }

  @Override
  public void onResume() {
    // The onResume() is a lie!  See TODO(fischman) in onPause() above.
    super.onResume();
    vsv.onResume();
  }

  @Override
  public void onIceServers(List<PeerConnection.IceServer> iceServers) {
    PeerConnectionFactory factory = new PeerConnectionFactory();

    MediaConstraints constraints = new MediaConstraints();
    constraints.mandatory.add(new MediaConstraints.KeyValuePair(
        "OfferToReceiveAudio", "true"));
    constraints.mandatory.add(new MediaConstraints.KeyValuePair(
        "OfferToReceiveVideo", "true"));
    pc = factory.createPeerConnection(iceServers, constraints, pcObserver);

    {
      logAndToast("Creating local video source...");
      VideoSource videoSource = factory.createVideoSource(
          VideoCapturer.create("Camera 1, Facing front, Orientation 270"),
          new MediaConstraints());
      LocalMediaStream lMS = factory.createLocalMediaStream("ARDAMS");
      VideoTrack videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
      videoTrack.addRenderer(new VideoRenderer(new VideoCallbacks(
          vsv, VideoStreamsView.Which.LOCAL)));
      lMS.addTrack(videoTrack);
      lMS.addTrack(factory.createAudioTrack("ARDAMSa0"));
      pc.addStream(lMS, new MediaConstraints());
    }
    logAndToast("Waiting for ICE candidates...");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
  }

  // Poor-man's assert(): die with |msg| unless |condition| is true.
  private static void abortUnless(boolean condition, String msg) {
    if (!condition) {
      throw new RuntimeException(msg);
    }
  }

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.e(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  // Send |json| to the underlying AppEngine Channel.
  private void sendMessage(JSONObject json) {
    appRtcClient.sendMessage(json.toString());
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  // Implementation detail: observe ICE & stream changes and react accordingly.
  private class PCObserver implements PeerConnection.Observer {
    @Override public void onIceCandidate(final IceCandidate candidate){
      runOnUiThread(new Runnable() {
          public void run() {
            JSONObject json = new JSONObject();
            jsonPut(json, "type", "candidate");
            jsonPut(json, "label", candidate.sdpMLineIndex);
            jsonPut(json, "id", candidate.sdpMid);
            jsonPut(json, "candidate", candidate.sdp);
            sendMessage(json);
          }
        });
    }

    @Override public void onError(){
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException("PeerConnection error!");
          }
        });
    }

    @Override public void onStateChange(final StateType stateChanged){
      runOnUiThread(new Runnable() {
          public void run() {
            // Since we trigger offering on GAE channel open, there's nothing
            // for us to do here.
          }
        });
    }

    @Override public void onAddStream(final MediaStream stream){
      runOnUiThread(new Runnable() {
          public void run() {
            abortUnless(stream.audioTracks.size() == 1 &&
                stream.videoTracks.size() == 1,
                "Weird-looking stream: " + stream);
            stream.videoTracks.get(0).addRenderer(new VideoRenderer(
                new VideoCallbacks(vsv, VideoStreamsView.Which.REMOTE)));
          }
        });
    }

    @Override public void onRemoveStream(final MediaStream stream){
      runOnUiThread(new Runnable() {
          public void run() {
            stream.videoTracks.get(0).dispose();
          }
        });
    }
  }

  // Implementation detail: handle offer creation/signaling and answer setting,
  // as well as adding remote ICE candidates once the answer SDP is set.
  private class SDPObserver implements SdpObserver {
    @Override public void onSuccess(final SessionDescription sdp) {
      runOnUiThread(new Runnable() {
          public void run() {
            logAndToast("Sending " + sdp.type);
            pc.setLocalDescription(sdpObserver, sdp);
            JSONObject json = new JSONObject();
            jsonPut(json, "type", sdp.type.canonicalForm());
            jsonPut(json, "sdp", sdp.description);
            sendMessage(json);
          }
        });
    }

    @Override public void onSuccess() {
      runOnUiThread(new Runnable() {
          public void run() {
            if (pc.getRemoteDescription() != null) {
              for (IceCandidate candidate : queuedRemoteCandidates) {
                pc.addIceCandidate(candidate);
              }
              queuedRemoteCandidates = null;
            }
          }
        });
    }

    @Override public void onFailure(final String error) {
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException("SDP error: " + error);
          }
        });
    }
  }

  // Implementation detail: handler for receiving GAE messages and dispatching
  // them appropriately.
  private class GAEHandler implements GAEChannelClient.MessageHandler {
    @JavascriptInterface public void onOpen() {
      logAndToast("Creating offer...");
      pc.createOffer(sdpObserver, new MediaConstraints());
    }

    @JavascriptInterface public void onMessage(String data) {
      try {
        JSONObject json = new JSONObject(data);
        String type = (String) json.get("type");
        if (type.equals("candidate")) {
          IceCandidate candidate = new IceCandidate(
              (String) json.get("id"),
              json.getInt("label"),
              (String) json.get("candidate"));
          if (queuedRemoteCandidates != null) {
            queuedRemoteCandidates.add(candidate);
          } else {
            pc.addIceCandidate(candidate);
          }
        } else if (type.equals("answer")) {
          SessionDescription answer = new SessionDescription(
              SessionDescription.Type.fromCanonicalForm(type),
              (String) json.get("sdp"));
          pc.setRemoteDescription(sdpObserver, answer);
        } else if (type.equals("bye")) {
          logAndToast("Remote end hung up; dropping PeerConnection");
          disconnectAndExit();
        } else {
          throw new RuntimeException("Unexpected message: " + data);
        }
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
    }

    @JavascriptInterface public void onClose() {
      disconnectAndExit();
    }

    @JavascriptInterface public void onError(int code, String description) {
      disconnectAndExit();
    }
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnectAndExit() {
    if (pc != null) {
      pc.dispose();
      pc = null;
    }
    appRtcClient.sendMessage("{'type': 'bye' }");
    appRtcClient.disconnect();
    finish();
  }

  // Implementation detail: bridge the VideoRenderer.Callbacks interface to the
  // VideoStreamsView implementation.
  private class VideoCallbacks implements VideoRenderer.Callbacks {
    private final VideoStreamsView view;
    private final VideoStreamsView.Which stream;

    public VideoCallbacks(
        VideoStreamsView view, VideoStreamsView.Which stream) {
      this.view = view;
      this.stream = stream;
    }

    @Override
    public void setSize(final int width, final int height) {
      view.queueEvent(new Runnable() {
          public void run() {
            view.setSize(stream, width, height);
          }
        });
    }

    @Override
    public void renderFrame(I420Frame frame) {
      // Paying for the copy of the YUV data here allows CSC and painting time
      // to get spent on the render thread instead of the UI thread.
      final I420Frame frameCopy = frame.deepCopy();
      view.queueEvent(new Runnable() {
          public void run() {
            view.updateFrame(stream, frameCopy);
          }
        });
    }
  }
}
