package com.fragmentation.combat.penetration;

import java.util.HashMap;
import java.util.Map;

public class TaczAmmoPenetrationConfig {

    private static final Map<String, Float> DEFAULT_AMMO_PENETRATION = new HashMap<>();
    private static final Map<String, Float> DEFAULT_GUN_PENETRATION = new HashMap<>();

    static {
        DEFAULT_AMMO_PENETRATION.put("tacz:9mm", 5.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:45acp", 6.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:357magnum", 8.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:556x45", 12.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:762x39", 14.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:545x39", 13.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:762x51", 18.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:338lapua", 25.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:50bmg", 35.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:12gauge", 4.0F);
        DEFAULT_AMMO_PENETRATION.put("tacz:20gauge", 3.0F);

        DEFAULT_GUN_PENETRATION.put("tacz:akm", 14.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:ak47", 14.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:ak74u", 13.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:ak_alpha", 14.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m4a1", 12.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m16a4", 12.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:hk416", 12.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:awm", 25.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m24", 18.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:kar98k", 16.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:mp5", 8.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:uzi", 6.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:p90", 10.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m870", 4.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m1014", 4.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m249", 12.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m240", 18.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:glock_17", 5.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:m1911", 6.0F);
        DEFAULT_GUN_PENETRATION.put("tacz:deagle", 10.0F);
    }

    public static float getPenetrationForAmmo(String ammoId) {
        return DEFAULT_AMMO_PENETRATION.getOrDefault(ammoId, 5.0F);
    }

    public static Float getPenetrationForGun(String gunId) {
        return DEFAULT_GUN_PENETRATION.get(gunId);
    }

    public static Float getPenetrationForGunId(String gunId) {
        if (gunId.equals("tacz:ak47") || gunId.equals("tacz:akm")) {
            return 25.0F;
        }
        if (gunId.equals("tacz:ak74u")) {
            return 23.0F;
        }
        if (gunId.equals("tacz:m4a1") || gunId.equals("tacz:hk416")) {
            return 22.0F;
        }
        if (gunId.equals("tacz:awm")) {
            return 45.0F;
        }
        if (gunId.equals("tacz:m24")) {
            return 35.0F;
        }
        if (gunId.equals("tacz:mp5")) {
            return 15.0F;
        }
        if (gunId.equals("tacz:p90")) {
            return 18.0F;
        }
        if (gunId.equals("tacz:m870") || gunId.equals("tacz:m1014")) {
            return 10.0F;
        }
        if (gunId.equals("tacz:glock_17")) {
            return 8.0F;
        }
        if (gunId.equals("tacz:deagle")) {
            return 20.0F;
        }
        return DEFAULT_GUN_PENETRATION.get(gunId);
    }

    public static void registerAmmoPenetration(String ammoId, float penetration) {
        DEFAULT_AMMO_PENETRATION.put(ammoId, penetration);
    }

    public static Map<String, Float> getAllConfigs() {
        return new HashMap<>(DEFAULT_AMMO_PENETRATION);
    }
}