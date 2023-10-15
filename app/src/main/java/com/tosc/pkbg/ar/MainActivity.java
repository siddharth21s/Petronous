package com.tosc.pkbg.ar;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.graphics.ColorUtils;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.tosc.pkbg.ar.game.Game;
import com.tosc.pkbg.ar.game.GameHit;
import com.tosc.pkbg.ar.game.GamePlayer;
import com.tosc.pkbg.ar.game.GameWorldObject;
import com.tosc.pkbg.ar.ml.MLKit;
import com.tosc.pkbg.ar.ml.TFMobile;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private CustomArFragment fragment;
    private Anchor cloudAnchor;

    private enum AppAnchorState {
        NONE,
        HOSTING,
        HOSTED,
        RESOLVING,
        RESOLVED
    }

    private AppAnchorState appAnchorState = AppAnchorState.NONE;

    private SnackbarHelper snackbarHelper = new SnackbarHelper();

    private StorageManager storageManager;

    private DatabaseReference gameRef = FirebaseDatabase.getInstance().getReference("games");
    private DatabaseReference gameWorldObjectsRef;
    private DatabaseReference gamePlayersRef;
    private DatabaseReference gameHitsRef;

    private Game game;
    private MLKit mlKit;
    private TFMobile tfMobile;
    private String gameId;

    private TextView tvHealth;
    private TextView tvGameStatus;
    private ProgressBar healthProgress;
    private ImageView bloodFrame;
    private ImageView headshotIndicator;
    private View btnShoot;
    private int currentHealth = -1;

    private boolean isReloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvHealth = findViewById(R.id.tv_health);
        tvGameStatus = findViewById(R.id.game_status);
        bloodFrame = findViewById(R.id.image_blood_frame);
        healthProgress = findViewById(R.id.healthProgress);
        headshotIndicator = findViewById(R.id.image_headshot);

        game = new Game();
        mlKit = new MLKit(this);
        tfMobile = new TFMobile(this);
        game.gameWorldObject = new ArrayList<>();


        fragment = (CustomArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);
        //To add
        fragment.getPlaneDiscoveryController().hide();
        fragment.getArSceneView().getScene().setOnUpdateListener(this::onUpdateFrame);

        Button clearButton = findViewById(R.id.clear_button);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setCloudAnchor(null);
            }
        });


        Button resolveButton = findViewById(R.id.resolve_button);
        resolveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (cloudAnchor != null){
                    snackbarHelper.showMessageWithDismiss(getParent(), "Please clear Anchor");
                    return;
                }
                ResolveDialogFragment dialog = new ResolveDialogFragment();
                dialog.setOkListener(MainActivity.this::onResolveOkPressed);
                dialog.show(getSupportFragmentManager(), "Resolve");

            }
        });

        Button resetButton = findViewById(R.id.reset_button);
        resetButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                resetGame();
            }
        });

        FrameLayout mainLayout = findViewById(R.id.layout_main);

        btnShoot = findViewById(R.id.shoot_button);
        btnShoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isReloading) {
                    Utils.playFireEmpty(MainActivity.this);
                    return;
                }
                isReloading = true;
                Utils.playFireNormal(MainActivity.this);

                fragment.captureBitmap(bitmap -> {
                    mlKit.detectFace(bitmap, () -> {
                        runOnUiThread(() -> {
                            onHitAttempted(true, GameHit.HIT_HEAD);
                            headshotIndicator.setVisibility(View.VISIBLE);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                headshotIndicator.setVisibility(View.GONE);
                            }, 500);
                        });
                    });

                    onHitAttempted(tfMobile.detectImage(bitmap), GameHit.HIT_BODY);
                }, false);
            }
        });

        fragment.setOnTapArPlaneListener(
                (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {

                    Anchor localAnchor = hitResult.createAnchor();

                    if (appAnchorState == AppAnchorState.NONE) {
                        Anchor newAnchor = fragment.getArSceneView().getSession().hostCloudAnchor(localAnchor);
                        setCloudAnchor(newAnchor);

                        appAnchorState = AppAnchorState.HOSTING;
                        snackbarHelper.showMessage(this, "Now hosting anchor...");

                        placeObject(fragment, cloudAnchor, Uri.parse("USMC_flag.sfb"), false);

                        return;
                    }

                    placeObject(fragment, localAnchor, Uri.parse("Pillar.sfb"), true);

                }
        );

        storageManager = new StorageManager(this);
    }

    private void resetGame() {
        GamePlayer player = new GamePlayer();
        player.playerId = getDeviceId();
        player.health = 100;

        DatabaseReference winnerIdRef = gameRef.child(gameId).child("winnerId");
        winnerIdRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                gamePlayersRef.child(getDeviceId()).child("health").setValue(100);
                if (dataSnapshot.getValue(String.class).equals("reset")) {
                    gameRef.child(gameId).child("winnerId").setValue("");
                } else {
                    gameRef.child(gameId).child("winnerId").setValue("reset");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {

            }
        });
    }

    private void onResolveOkPressed(String dialogValue){
        int shortCode = Integer.parseInt(dialogValue);
        setupNewGame(shortCode);
        storageManager.getCloudAnchorID(shortCode,(cloudAnchorId) -> {
            Anchor resolvedAnchor = fragment.getArSceneView().getSession().resolveCloudAnchor(cloudAnchorId);
            setCloudAnchor(resolvedAnchor);
            placeObject(fragment, cloudAnchor, Uri.parse("USMC_flag.sfb"), false);
            snackbarHelper.showMessage(this, "Now Resolving Anchor...");
            appAnchorState = AppAnchorState.RESOLVING;
            addChildSyncing();
        });
    }



    private void setCloudAnchor (Anchor newAnchor){
        if (cloudAnchor != null){
            cloudAnchor.detach();
        }

        cloudAnchor = newAnchor;
        appAnchorState = AppAnchorState.NONE;
        snackbarHelper.hide(this);
    }

    private void onUpdateFrame(FrameTime frameTime){
        checkUpdatedAnchor();
    }

    private synchronized void checkUpdatedAnchor(){
        if (appAnchorState != AppAnchorState.HOSTING && appAnchorState != AppAnchorState.RESOLVING){
            return;
        }
        Anchor.CloudAnchorState cloudState = cloudAnchor.getCloudAnchorState();
        if (appAnchorState == AppAnchorState.HOSTING) {
            if (cloudState.isError()) {
                snackbarHelper.showMessageWithDismiss(this, "Error hosting anchor.. "
                        + cloudState);
                appAnchorState = AppAnchorState.NONE;
            } else if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
                storageManager.nextShortCode((shortCode) -> {
                    if (shortCode == null) {
                        snackbarHelper.showMessageWithDismiss(this, "Could not get shortCode");
                        return;
                    }
                    storageManager.storeUsingShortCode(shortCode, cloudAnchor.getCloudAnchorId());
                    setupNewGame(shortCode);

                    snackbarHelper.showMessageWithDismiss(this, "Anchor hosted! Cloud Short Code: " +
                            shortCode);

                    addChildSyncing();
                });

                appAnchorState = AppAnchorState.HOSTED;
            }
        } else if (appAnchorState == AppAnchorState.RESOLVING) {
            if (cloudState.isError()) {
                snackbarHelper.showMessageWithDismiss(this, "Error resolving anchor.. "
                        + cloudState);
                appAnchorState = AppAnchorState.NONE;
            } else if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
                snackbarHelper.showMessageWithDismiss(this, "Anchor resolved successfully");
                appAnchorState = AppAnchorState.RESOLVED;
            }
        }

    }


    private void placeObject(ArFragment fragment, Anchor anchor, Uri model, boolean shouldSync) {

        ModelRenderable.builder()
                .setSource(fragment.getContext(), model)
                .build()
                .thenAccept(renderable -> addNodeToScene(fragment, anchor, renderable, shouldSync))
                .exceptionally((throwable -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setMessage(throwable.getMessage())
                            .setTitle("Error!");
                    AlertDialog dialog = builder.create();
                    dialog.show();
                    return null;
                }));
    }

    private void addNodeToScene(ArFragment fragment, Anchor anchor, Renderable renderable, boolean shouldSync) {
        AnchorNode anchorNode = new AnchorNode(anchor);
        TransformableNode node = new TransformableNode(fragment.getTransformationSystem());
        node.setRenderable(renderable);
        node.setParent(anchorNode);
        fragment.getArSceneView().getScene().addChild(anchorNode);
        node.select();
        if (shouldSync)
            syncNewObject(anchorNode);
    }

    private void setupNewGame(int shortCode) {
        gameId = String.valueOf(shortCode);
        game.id = gameId;
        gameWorldObjectsRef = gameRef.child(gameId).child("objects");
        gamePlayersRef = gameRef.child(gameId).child("players");
        gameHitsRef = gameRef.child(gameId).child("hits");

        GamePlayer player = new GamePlayer();
        player.playerId = getDeviceId();
        player.health = 100;

        gameRef.child(gameId).child("winnerId").setValue("");

        gamePlayersRef.child(getDeviceId()).setValue(player);

        gamePlayersRef.child(getDeviceId()).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                updateGameState(dataSnapshot.getValue(GamePlayer.class));
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        gameRef.child(gameId).child("winnerId").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                String winnerId = dataSnapshot.getValue(String.class);
                if (winnerId == null || winnerId.equals("") || winnerId.equals(" ")) {
                    btnShoot.setVisibility(View.VISIBLE);
                    tvGameStatus.setVisibility(View.GONE);
                    bloodFrame.setVisibility(View.GONE);
                    findViewById(R.id.reset_button).setVisibility(View.GONE);
                    findViewById(R.id.iv_crosshair).setVisibility(View.VISIBLE);
                    return;
                }

                if (winnerId.equals("reset")) {
                    findViewById(R.id.reset_button).setVisibility(View.VISIBLE);
                    return;
                }

                btnShoot.setVisibility(View.GONE);
                tvGameStatus.setVisibility(View.VISIBLE);
                findViewById(R.id.reset_button).setVisibility(View.VISIBLE);
                findViewById(R.id.iv_crosshair).setVisibility(View.GONE);
                if (winnerId.equals(getDeviceId())) {
                    tvGameStatus.setText("WINNER WINNER CHICKEN DINNER");
                } else  {
                    tvGameStatus.setText("LOST!");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void syncNewObject(AnchorNode anchorNode) {
        Vector3 position = anchorNode.getWorldPosition();
        Quaternion rotation = anchorNode.getWorldRotation();
        DatabaseReference newObjectRef = gameWorldObjectsRef.push();
        GameWorldObject worldObject = new GameWorldObject(position, rotation, newObjectRef.getKey(), getDeviceId());
        game.gameWorldObject.add(worldObject);
        newObjectRef.setValue(worldObject);
    }

    private void addChildSyncing() {
        gameWorldObjectsRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                try {
                    GameWorldObject worldObject = dataSnapshot.getValue(GameWorldObject.class);
                    if (worldObject.addedByDeviceId.equals(getDeviceId())) {
                        return;
                    }
                    Session session = fragment.getArSceneView().getSession();
                    Anchor anchor = session.createAnchor(new Pose(getArray(worldObject.position), getArray(worldObject.rotation)));
                    placeObject(fragment, anchor, Uri.parse("Pillar.sfb"), false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {

            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
        gameWorldObjectsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    try {
                        GameWorldObject worldObject = snapshot.getValue(GameWorldObject.class);
                        Session session = fragment.getArSceneView().getSession();
                        Anchor anchor = session.createAnchor(new Pose(getArray(worldObject.position), getArray(worldObject.rotation)));
                        placeObject(fragment, anchor, Uri.parse("Pillar.sfb"), false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private float[] getArray(ArrayList<Float> list) {
        float[] floatArray = new float[list.size()];
        int i = 0;

        for (Float f : list) {
            floatArray[i++] = (f != null ? f : Float.NaN);
        }

        return floatArray;
    }

    private String getDeviceId() {
        return Settings.Secure.getString(this.getContentResolver(),
                Settings.Secure.ANDROID_ID).substring(0, 5);
    }

    private void onHitAttempted(boolean isHit, int hitType) {
        if (hitType == GameHit.HIT_HEAD && isHit) {
            Utils.playFireHeadshot(this);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Utils.playReload(this);
            isReloading = false;
        }, 1000);

        if (isHit) {
            GameHit hit = new GameHit(getDeviceId(), hitType);
            if (gameHitsRef != null) {
                gameHitsRef.push().setValue(hit);
            }
        }
    }

    private void updateGameState(GamePlayer player) {
        if (currentHealth != -1) {
            int damage = currentHealth - player.health;

            if (damage >= 30) {
                Utils.playPainHeadshot(this);
                Utils.vibrate(this, 1000);
            } else if (damage > 0) {
                Utils.playPainNormal(this);
                Utils.vibrate(this, 500);
            }
        }
        if (player.health < 30) {
            bloodFrame.setVisibility(View.VISIBLE);
        }
        if (player.health < 20) {
            bloodFrame.setAlpha(0.8f);
        }
        currentHealth = player.health;
        tvHealth.setText(String.valueOf(player.health));

        healthProgress.setProgress(currentHealth);

        if (currentHealth <= 30) {
            setHealthProgressColor(Color.RED);
        }

        if (currentHealth > 30 && currentHealth <= 70) {
            setHealthProgressColor(Color.YELLOW);
        }

        if (currentHealth > 70) {
            setHealthProgressColor(Color.GREEN);
        }
    }

    private void setHealthProgressColor(int color) {
        healthProgress.setVisibility(View.VISIBLE);
        color =  ColorUtils.setAlphaComponent(color, 60);
        healthProgress.setProgressTintList(ColorStateList.valueOf(color));

    }
}
