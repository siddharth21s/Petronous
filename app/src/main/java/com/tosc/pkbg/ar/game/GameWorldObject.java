package com.tosc.pkbg.ar.game;

import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;

import java.util.ArrayList;
import java.util.Arrays;

public class GameWorldObject {

    public ArrayList<Float> position;
    public ArrayList<Float> rotation;
    public String id;
    public String addedByDeviceId;

    public GameWorldObject() {

    }

    public GameWorldObject(Vector3 pos, Quaternion rot, String id, String addedByDeviceId) {
        Float[] positionArray = new Float[] {pos.x, pos.y, pos.z};
        this.position = new ArrayList<>(Arrays.asList(positionArray));
        Float[] rotationArray = new Float[] {rot.x, rot.y, rot.z, rot.w};
        this.rotation = new ArrayList<>(Arrays.asList(rotationArray));
        this.id = id;
        this.addedByDeviceId = addedByDeviceId;
    }
}
