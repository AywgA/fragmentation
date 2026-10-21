package com.fragmentation.ballistics;

public final class BallisticsMathTest {
    private BallisticsMathTest() {
    }

    public static void main(String[] args) {
        assertNear(800.0D, BallisticsMath.initialVelocity(300.0D, 800.0D));
        assertNear(0.00264D, BallisticsMath.airDragCoefficient(0));
        assertNear(0.00250D, BallisticsMath.airDragCoefficient(15));
        assertNear(0.00238D, BallisticsMath.airDragCoefficient(30));
        assertNear(0.00230D, BallisticsMath.airDragCoefficient(40));
        assertNear(0.00250D, BallisticsMath.airDragCoefficient(20));
        assertTrue(BallisticsMath.isSupportedAirTemperature(30));
        assertFalse(BallisticsMath.isSupportedAirTemperature(20));

        assertNear(
            20.0D,
            BallisticsMath.lateralWindSpeed(1.0D, 0.0D, 20.0D, 1.0D, 0.0D)
        );
        assertNear(
            -20.0D,
            BallisticsMath.lateralWindSpeed(-1.0D, 0.0D, 20.0D, 1.0D, 0.0D)
        );
        assertNear(
            0.0D,
            BallisticsMath.lateralWindSpeed(0.0D, -1.0D, 20.0D, 1.0D, 0.0D)
        );

        double nextForward = BallisticsMath.nextForwardVelocity(
            800.0D,
            0.0001D,
            BallisticsMath.TICK_SECONDS
        );
        assertNear(796.8D, nextForward);

        double nextDown = BallisticsMath.nextDownwardVelocity(
            0.0D,
            9.81D,
            0.0001D,
            BallisticsMath.TICK_SECONDS
        );
        assertNear(0.4905D, nextDown);
        assertEquals(0, BallisticsMath.requiredArcLifetimeTicks(0.0D, 9.81D));
        assertEquals(0, BallisticsMath.requiredArcLifetimeTicks(160.0D, 0.0D));
        assertEquals(
            693,
            BallisticsMath.requiredArcLifetimeTicks(160.0D, 9.81D)
        );
        assertEquals(
            BallisticsMath.MAX_TRAJECTORY_LIFETIME_TICKS,
            BallisticsMath.requiredArcLifetimeTicks(10_000.0D, 9.81D)
        );

        assertNear(
            0.125D,
            BallisticsMath.estimateFlightTime(100.0D, 0.0D, 300.0D, 800.0D)
        );
        assertNear(0.0D, BallisticsMath.lagTime(0.1D, 100.0D, 800.0D));
        assertNear(0.025D, BallisticsMath.lagTime(0.15D, 100.0D, 800.0D));

        assertNear(1.0D, BallisticsMath.speedRatio(800.0D, 800.0D));
        assertNear(0.5D, BallisticsMath.speedRatio(400.0D, 800.0D));
        assertNear(1.0D, BallisticsMath.speedRatio(900.0D, 800.0D));
        assertNear(1.0D, BallisticsMath.energyRetention(800.0D, 800.0D));
        assertNear(0.5D, BallisticsMath.energyRetention(400.0D, 800.0D));
        assertNear(25.0D, BallisticsMath.retainedValue(100.0D, 0.25D));
        assertNear(5.0D, BallisticsMath.retainedArmorDamage(20.0D, 0.25D));
        assertNear(77.0D, BallisticsMath.retainedValue(77.0D, 1.0D));
        assertNear(30.0D, BallisticsMath.retainedValue(30.0D, 1.0D));
        assertNear(20.0D, BallisticsMath.retainedArmorDamage(20.0D, 1.0D));

        double bulletMass = 0.008D;
        double bulletRadius = 0.004D;
        double currentSpeed = 800.0D;
        double penetrationThreshold = 40_000_000.0D;
        assertNear(2560.0D, BallisticsMath.kineticEnergy(bulletMass, currentSpeed));
        assertNear(
            Math.PI * bulletRadius * bulletRadius,
            BallisticsMath.bulletCrossSection(bulletRadius)
        );
        assertTrue(BallisticsMath.canPenetrateCover(
            bulletMass,
            currentSpeed,
            penetrationThreshold,
            bulletRadius
        ));
        assertFalse(BallisticsMath.canPenetrateCover(
            bulletMass,
            600.0D,
            penetrationThreshold,
            bulletRadius
        ));
        assertTrue(BallisticsMath.canPenetrateCover(
            0.0D,
            0.0D,
            0.0D,
            0.0D
        ));
        double postPenetrationSpeed = BallisticsMath.postPenetrationSpeed(
            currentSpeed,
            0.2D,
            0.5D
        );
        assertNear(720.0D, postPenetrationSpeed);
        double coverAttenuation = Math.pow(0.9D, 0.85D);
        assertNear(
            coverAttenuation,
            BallisticsMath.coverAttenuation(postPenetrationSpeed, currentSpeed)
        );
        assertNear(
            100.0D * coverAttenuation,
            BallisticsMath.retainedValue(100.0D, coverAttenuation)
        );
        assertNear(
            20.0D * coverAttenuation,
            BallisticsMath.retainedArmorDamage(20.0D, coverAttenuation)
        );
        assertNear(
            0.0D,
            BallisticsMath.postPenetrationSpeed(currentSpeed, 2.0D, 1.0D)
        );

        assertTrue(BallisticsMath.canFragment(0.008D, 800.0D));
        assertFalse(BallisticsMath.canFragment(0.0003D, 800.0D));
        double zeroPenetrationFragmentation =
            BallisticsMath.fragmentationProbability(
                0.25D,
                200.0D,
                800.0D,
                0.0D
            );
        double twentyPenetrationFragmentation =
            BallisticsMath.fragmentationProbability(
                0.25D,
                200.0D,
                800.0D,
                20.0D
            );
        assertNear(
            0.34375D,
            zeroPenetrationFragmentation
        );
        assertNear(
            0.234375D,
            twentyPenetrationFragmentation
        );
        assertTrue(
            zeroPenetrationFragmentation
                > twentyPenetrationFragmentation
        );
        assertNear(
            1.0D,
            BallisticsMath.fragmentationProbability(
                1.0D,
                800.0D,
                800.0D,
                0.0D
            )
        );
        assertNear(0.25D, BallisticsMath.fragmentDamageMultiplier(2));
        assertNear(1.0D / 6.0D, BallisticsMath.fragmentDamageMultiplier(3));
        assertNear(20.0D, BallisticsMath.fragmentPenetration(80.0D));

        assertTrue(BallisticsMath.isRicochetAngle(45.0D));
        assertFalse(BallisticsMath.isRicochetAngle(45.01D));
        assertNear(
            0.4D,
            BallisticsMath.ricochetProbability(
                0.5D,
                0.8D,
                0.0D,
                800.0D,
                800.0D
            )
        );
        assertNear(
            1.0D,
            BallisticsMath.ricochetProbability(
                1.0D,
                1.0D,
                45.0D,
                800.0D,
                800.0D
            )
        );
        double angleCorrection = 1.0D - Math.sqrt(45.0D / 90.0D);
        double expectedRicochet = 0.5D * 0.8D
            * (0.7D + 0.3D * angleCorrection)
            * (0.6D + 0.4D * 0.5D);
        assertNear(
            expectedRicochet,
            BallisticsMath.ricochetProbability(
                0.5D,
                0.8D,
                45.0D,
                400.0D,
                800.0D
            )
        );
        assertNear(
            0.0D,
            BallisticsMath.ricochetProbability(
                1.0D,
                1.0D,
                60.0D,
                800.0D,
                800.0D
            )
        );
        assertNear(0.7D, BallisticsMath.RICOCHET_PENETRATION_MULTIPLIER);

        assertNear(0.4D, BallisticsMath.firstHitPenetrationChance(76.0D, 60.0D));
        assertNear(1.0D, BallisticsMath.firstHitPenetrationChance(77.0D, 60.0D));
        assertNear(0.0D, BallisticsMath.firstHitPenetrationChance(75.0D, 60.0D));
        assertNear(1.0D, BallisticsMath.firstHitPenetrationChance(90.0D, 60.0D));
        assertNear(1.0D, BallisticsMath.firstHitPenetrationChance(91.0D, 60.0D));

        double frameEndVelocity = BallisticsMath.nextForwardVelocity(
            900.0D,
            0.00250D,
            BallisticsMath.TICK_SECONDS
        );
        assertNear(798.75D, frameEndVelocity);
        assertNear(900.0D, BallisticsMath.velocityAtFrameFraction(900.0D, frameEndVelocity, 0.0D));
        assertNear(849.375D, BallisticsMath.velocityAtFrameFraction(900.0D, frameEndVelocity, 0.5D));
        assertNear(798.75D, BallisticsMath.velocityAtFrameFraction(900.0D, frameEndVelocity, 1.0D));

        double configuredBulletSpeed = 1200.0D;
        double configuredMuzzleVelocity = 800.0D;
        double maximumBulletSpeed = BallisticsMath.initialVelocity(
            configuredBulletSpeed,
            configuredMuzzleVelocity
        );
        assertNear(800.0D, maximumBulletSpeed);
        assertNear(1.0D, BallisticsMath.speedRatio(800.0D, maximumBulletSpeed));
        assertNear(1.0D, BallisticsMath.energyRetention(800.0D, maximumBulletSpeed));

        assertNear(1.0D, BallisticsMath.durabilityMoaMultiplier(1.0D));
        assertNear(1.1D, BallisticsMath.durabilityMoaMultiplier(0.75D));
        assertNear(1.2D, BallisticsMath.durabilityMoaMultiplier(0.5D));
        assertNear(2.1075676941479466D, BallisticsMath.durabilityMoaMultiplier(0.4D));
        assertNear(2.9236021786828825D, BallisticsMath.durabilityMoaMultiplier(0.25D));
        assertNear(3.595085503980261D, BallisticsMath.durabilityMoaMultiplier(0.1D));
        assertNear(4.0D, BallisticsMath.durabilityMoaMultiplier(0.0D));
    }

    private static void assertNear(double expected, double actual) {
        if (Math.abs(expected - actual) > 1.0E-9D) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }
}
