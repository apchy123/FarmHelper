package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.failsafe.FailsafeManager;
import com.jelly.farmhelperv2.feature.FeatureManager;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.pathfinder.FlyPathFinderExecutor;
import com.jelly.farmhelperv2.util.*;
import com.jelly.farmhelperv2.util.helper.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.util.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFishingRod;

import java.lang.Math;

public class PestFarmer implements IFeature {

    public static PestFarmer instance = new PestFarmer();
    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean enabled = false;
    private long pestSpawnTime = 0L;
    private int swapTo = -1;
    private List<String> equipments = new ArrayList();
    private MainState mainState = MainState.NONE;
    private State state = State.SWAPPING;
    private ReturnState returnState = ReturnState.STARTING;
    private boolean pestSpawned = false;
    private boolean kill = false;
    public boolean wasSpawnChanged = false;
    private Clock timer = new Clock();
    private boolean rodCasted = false;

    // return
    private boolean isRewarpObstructed = false;
    private Optional<BlockPos> preTpBlockPos = Optional.empty();
    private int flyAttempts = 0;
    private int mainAttempts = 0; // this isnt needed, like at all, but im adding this because fuck you thats why
    private boolean failed = false;
    private float[] requiredAng = new float[2];

    @Override
    public String getName() {
        return "PestFarmer";
    }

    @Override
    public boolean isRunning() {
        return enabled;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        state = State.SWAPPING;
        mainState = MainState.NONE;
        returnState = ReturnState.STARTING;
        swapTo = -1;
        equipments.clear();
        enabled = false;
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.pestFarming;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return (mainState != MainState.RETURN || returnState.ordinal() > 9) && (state != State.WAITING_FOR_WARP && state != State.ENDING);
    }

    @Override
    public void start() {
        if (enabled) {
            return;
        }
        requiredAng = new float[] {
                AngleUtils.get360RotationYaw(MacroHandler.getInstance().getCurrentMacro().get().getYaw()),
                MacroHandler.getInstance().getCurrentMacro().get().getPitch()
        };
        MacroHandler.getInstance().pauseMacro();
        enabled = true;
        IFeature.super.start();
    }

    @Override
    public void stop() {
        if (!enabled) {
            return;
        }

        enabled = false;
        kill = false;
        equipments = new ArrayList<>();
        mainState = MainState.NONE;
        state = State.SWAPPING;
        returnState = ReturnState.STARTING;
        preTpBlockPos = Optional.empty();
        flyAttempts = 0;
        mainAttempts = 0;
        requiredAng = new float[2];
        rodCasted = false;
        if (failed) {
            MacroHandler.getInstance().disableMacro();
            LogUtils.sendError("Failed, disabling");
        }
        if (!failed && MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().resumeMacro();
        }
        failed = false;
        IFeature.super.stop();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTick(ClientTickEvent event) {
        if (event.phase != Phase.START) {
            return;
        }
        if (!this.isToggled() || !MacroHandler.getInstance().isCurrentMacroEnabled() || MacroHandler.getInstance().getCurrentMacro().get().currentState.ordinal() < 4 || enabled) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (!GameStateHandler.getInstance().inGarden()) {
            return;
        }
        if (GameStateHandler.getInstance().getServerClosingSeconds().isPresent()) {
            return;
        }
        if (!Scheduler.getInstance().isFarming()) {
            return;
        }
        if (FailsafeManager.getInstance().triggeredFailsafe.isPresent()) {
            return;
        }
        if (FeatureManager.getInstance().isAnyOtherFeatureEnabled(this)) {
            return;
        }

        if (wasSpawnChanged && PlayerUtils.isStandingOnRewarpLocation()) {
            mainState = MainState.RETURN;
            start();
            return;
        }

        if (pestSpawned) {
            long timeDiff = System.currentTimeMillis() - pestSpawnTime;
            if (timeDiff >= FarmHelperConfig.pestFarmingWaitTime * 1000L) {
                pestSpawned = false;
                if (AutoWardrobe.activeSlot != FarmHelperConfig.pestFarmingBiohazardSlot) {
                    LogUtils.sendDebug("Swapping to " + FarmHelperConfig.pestFarmingBiohazardSlot);
                    swapTo = FarmHelperConfig.pestFarmingBiohazardSlot;
                    if (FarmHelperConfig.pestFarmingSwapEq) {
                        equipments = Arrays.asList(FarmHelperConfig.pestFarmingEq1.split("\\|"));
                    }
                    mainState = MainState.SWAP_N_START;
                    start();
                }
            } else if (AutoWardrobe.activeSlot != FarmHelperConfig.pestFarmingFermentoSlot) {
                LogUtils.sendDebug("Swapping to " + FarmHelperConfig.pestFarmingFermentoSlot);
                swapTo = FarmHelperConfig.pestFarmingFermentoSlot;
                if (FarmHelperConfig.pestFarmingSwapEq) {
                    equipments = Arrays.asList(FarmHelperConfig.pestFarmingEq0.split("\\|"));
                }
                mainState = MainState.SWAP_N_START;
                start();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type != 0) {
            return;
        }
        String message = event.message.getUnformattedText();
        if (message.contains("§6§lYUCK!") || message.startsWith("§6§lEWW!") || message.startsWith("§6§lGROSS!")) {
            pestSpawnTime = System.currentTimeMillis();
            pestSpawned = true;
            LogUtils.sendDebug("[PestFarmer] Pest Spawned.");
        }

        if (!enabled || (state != State.WAITING_FOR_SPAWN && returnState != ReturnState.WAITING_FOR_SPAWN && returnState != ReturnState.WAITING_FOR_SPAWN_2)) return;
        if (message.contains("Your spawn location has been set!")) {
            event.setCanceled(true);
            mc.thePlayer.addChatMessage(event.message);
            wasSpawnChanged = true;
            if (mainState == MainState.SWAP_N_START) {
                if (!FarmHelperConfig.pestFarmerKillPests && FarmHelperConfig.pestFarmingSetSpawn) {
                    setState(State.ENDING, 0);
                } else {
                    setState(State.TOGGLING_PEST_DESTROYER, 0);
                }
            } else {
                if (returnState.ordinal() == 1) {
                    setState(ReturnState.TP_TO_SPAWN_PLOT, 0);
                }
                else {
					setState(ReturnState.ENDING, FarmHelperConfig.getRandomGUIMacroDelay());
                    wasSpawnChanged = false;
                }
            }
            return;
        }

        if (message.contains("You cannot set your spawn here!")) {
            LogUtils.sendError("Could not set spawn, returning to farming");
            stop();
        }
    }

    package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.failsafe.FailsafeManager;
import com.jelly.farmhelperv2.feature.FeatureManager;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.pathfinder.FlyPathFinderExecutor;
import com.jelly.farmhelperv2.util.*;
import com.jelly.farmhelperv2.util.helper.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.util.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFishingRod;

import java.lang.Math;

public class PestFarmer implements IFeature {

    public static PestFarmer instance = new PestFarmer();
    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean enabled = false;
    private long pestSpawnTime = 0L;
    private int swapTo = -1;
    private List<String> equipments = new ArrayList();
    private MainState mainState = MainState.NONE;
    private State state = State.SWAPPING;
    private ReturnState returnState = ReturnState.STARTING;
    private boolean pestSpawned = false;
    private boolean kill = false;
    public boolean wasSpawnChanged = false;
    private Clock timer = new Clock();
    private boolean rodCasted = false;

    // return
    private boolean isRewarpObstructed = false;
    private Optional<BlockPos> preTpBlockPos = Optional.empty();
    private int flyAttempts = 0;
    private int mainAttempts = 0;
    private boolean failed = false;
    private float[] requiredAng = new float[2];

    @Override
    public String getName() {
        return "PestFarmer";
    }

    @Override
    public boolean isRunning() {
        return enabled;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        state = State.SWAPPING;
        mainState = MainState.NONE;
        returnState = ReturnState.STARTING;
        swapTo = -1;
        equipments.clear();
        enabled = false;
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.pestFarming;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return (mainState != MainState.RETURN || returnState.ordinal() > 9) && (state != State.WAITING_FOR_WARP && state != State.ENDING);
    }

    @Override
    public void start() {
        if (enabled) {
            return;
        }
        rodCasted = false; // reset rod cast
        requiredAng = new float[] {
                AngleUtils.get360RotationYaw(MacroHandler.getInstance().getCurrentMacro().get().getYaw()),
                MacroHandler.getInstance().getCurrentMacro().get().getPitch()
        };
        MacroHandler.getInstance().pauseMacro();
        enabled = true;
        IFeature.super.start();
    }

    @Override
    public void stop() {
        if (!enabled) {
            return;
        }

        enabled = false;
        rodCasted = false; // reset rod cast
        kill = false;
        equipments = new ArrayList<>();
        mainState = MainState.NONE;
        state = State.SWAPPING;
        returnState = ReturnState.STARTING;
        preTpBlockPos = Optional.empty();
        flyAttempts = 0;
        mainAttempts = 0;
        requiredAng = new float[2];
        if (failed) {
            MacroHandler.getInstance().disableMacro();
            LogUtils.sendError("Failed, disabling");
        }
        if (!failed && MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().resumeMacro();
        }
        failed = false;
        IFeature.super.stop();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTick(ClientTickEvent event) {
        if (event.phase != Phase.START) {
            return;
        }
        if (!this.isToggled() || !MacroHandler.getInstance().isCurrentMacroEnabled() || MacroHandler.getInstance().getCurrentMacro().get().currentState.ordinal() < 4 || enabled) {
            return;
        }
        if (mc.currentScreen != null) return;
        if (!GameStateHandler.getInstance().inGarden()) return;
        if (GameStateHandler.getInstance().getServerClosingSeconds().isPresent()) return;
        if (!Scheduler.getInstance().isFarming()) return;
        if (FailsafeManager.getInstance().triggeredFailsafe.isPresent()) return;
        if (FeatureManager.getInstance().isAnyOtherFeatureEnabled(this)) return;

        if (wasSpawnChanged && PlayerUtils.isStandingOnRewarpLocation()) {
            mainState = MainState.RETURN;
            start();
            return;
        }

        if (pestSpawned) {
            long timeDiff = System.currentTimeMillis() - pestSpawnTime;
            if (timeDiff >= FarmHelperConfig.pestFarmingWaitTime * 1000L) {
                pestSpawned = false;
                if (AutoWardrobe.activeSlot != FarmHelperConfig.pestFarmingBiohazardSlot) {
                    LogUtils.sendDebug("Swapping to " + FarmHelperConfig.pestFarmingBiohazardSlot);
                    swapTo = FarmHelperConfig.pestFarmingBiohazardSlot;
                    if (FarmHelperConfig.pestFarmingSwapEq) {
                        equipments = Arrays.asList(FarmHelperConfig.pestFarmingEq1.split("\\|"));
                    }
                    mainState = MainState.SWAP_N_START;
                    start();
                }
            } else if (AutoWardrobe.activeSlot != FarmHelperConfig.pestFarmingFermentoSlot) {
                LogUtils.sendDebug("Swapping to " + FarmHelperConfig.pestFarmingFermentoSlot);
                swapTo = FarmHelperConfig.pestFarmingFermentoSlot;
                if (FarmHelperConfig.pestFarmingSwapEq) {
                    equipments = Arrays.asList(FarmHelperConfig.pestFarmingEq0.split("\\|"));
                }
                mainState = MainState.SWAP_N_START;
                start();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type != 0) return;

        String message = event.message.getUnformattedText();
        if (message.contains("§6§lYUCK!") || message.startsWith("§6§lEWW!") || message.startsWith("§6§lGROSS!")) {
            pestSpawnTime = System.currentTimeMillis();
            pestSpawned = true;
            LogUtils.sendDebug("[PestFarmer] Pest Spawned.");
        }

        if (!enabled || (state != State.WAITING_FOR_SPAWN && returnState != ReturnState.WAITING_FOR_SPAWN && returnState != ReturnState.WAITING_FOR_SPAWN_2)) return;

        if (message.contains("Your spawn location has been set!")) {
            event.setCanceled(true);
            mc.thePlayer.addChatMessage(event.message);
            wasSpawnChanged = true;
            if (mainState == MainState.SWAP_N_START) {
                if (!FarmHelperConfig.pestFarmerKillPests && FarmHelperConfig.pestFarmingSetSpawn) {
                    setState(State.ENDING, 0);
                } else {
                    setState(State.TOGGLING_PEST_DESTROYER, 0);
                }
            } else {
                if (returnState.ordinal() == 1) {
                    setState(ReturnState.TP_TO_SPAWN_PLOT, 0);
                } else {
                    setState(ReturnState.ENDING, FarmHelperConfig.getRandomGUIMacroDelay());
                    wasSpawnChanged = false;
                }
            }
            return;
        }

        if (message.contains("You cannot set your spawn here!")) {
            LogUtils.sendError("Could not set spawn, returning to farming");
            stop();
        }
    }

    @SubscribeEvent
    public void onTickSwap(ClientTickEvent event) {
        if (!enabled || event.phase != Phase.START) return;

        switch (mainState) {
            case NONE:
                stop();
                break;

            case SWAP_N_START: {
                switch (state) {
                    case SWAPPING:
                        AutoWardrobe.instance.swapTo(swapTo, equipments);
                        setState(State.WAITING_FOR_SWAP, 0);
                        break;

                    case WAITING_FOR_SWAP:
                        if (AutoWardrobe.instance.isRunning()) return;

                        // Biohazard set handling
                        if (swapTo == FarmHelperConfig.pestFarmingBiohazardSlot && pestSpawned) {
                            if (FarmHelperConfig.pestFarmerCastRod && !rodCasted) {
                                ItemStack heldItem = mc.thePlayer.getHeldItem();

                                if (heldItem == null || !(heldItem.getItem() instanceof ItemFishingRod)) {
                                    for (int i = 0; i < 9; i++) {
                                        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                                        if (stack != null && stack.getItem() instanceof ItemFishingRod) {
                                            mc.thePlayer.inventory.currentItem = i;
                                            break;
                                        }
                                    }
                                }

                                KeyBindUtils.rightClick();
                                rodCasted = true;
                                LogUtils.sendDebug("[PestFarmer] Casted fishing rod after Biohazard swap.");
                            }

                            if (FarmHelperConfig.pestFarmerKillPests) {
                                setState(State.TOGGLING_PEST_DESTROYER, 0);
                            } else {
                                stop();
                            }
                            break;
                        }

                        // Fermento set handling
                        if (swapTo == FarmHelperConfig.pestFarmingFermentoSlot && pestSpawned) {
                            stop();
                            break;
                        }

                        stop();
                        break;

                    case SETTING_SPAWN:
                        mc.thePlayer.sendChatMessage("/setspawn");
                        setState(State.WAITING_FOR_SPAWN, 5000);
                        break;

                    case WAITING_FOR_SPAWN:
                        if (hasTimerEnded()) {
                            LogUtils.sendError("Could not verify spawn change under 5 seconds, disabling");
                            stop();
                        }
                        break;

                    case TOGGLING_PEST_DESTROYER:
                        if (PestsDestroyer.getInstance().canEnableMacro(true)) {
                            PestsDestroyer.getInstance().start();
                            setState(State.WAITING_FOR_PEST_DESTROYER, 0);
                        } else {
                            LogUtils.sendError("Cannot enable PestsDestroyer. Please turn it on from the PestsDestroyer tab.");
                            stop();
                        }
                        break;

                    case WAITING_FOR_PEST_DESTROYER:
                        if (PestsDestroyer.getInstance().isRunning() || isTimerRunning()) return;

                        if (FarmHelperConfig.pestFarmerCastRod && !rodCasted) {
                            KeyBindUtils.rightClick();
                            rodCasted = true;
                            timer.schedule(200);
                            break;
                        }

                        mc.thePlayer.sendChatMessage("/warp garden");
                        setState(State.WAITING_FOR_WARP, 5000);
                        preTpBlockPos = Optional.of(mc.thePlayer.getPosition());
                        break;

                    case WAITING_FOR_WARP:
                        if (hasTimerEnded() || !preTpBlockPos.isPresent()) {
                            LogUtils.sendError("Could not warp to garden properly. Stopping PestFarmer.");
                            setState(State.ENDING, 0);
                            failed = true;
                            break;
                        }
                        if (!preTpBlockPos.get().equals(mc.thePlayer.getPosition()) && mc.theWorld.isBlockLoaded(mc.thePlayer.getPosition())) {
                            setState(State.ENDING, FarmHelperConfig.getRandomGUIMacroDelay());
                        }
                        break;

                    case ENDING:
                        if (!isTimerRunning()) stop();
                        break;
                }
                break;
            }

            case RETURN:
                handleReturnStates();
                break;
        }
    }

    // Keep your existing helper methods like isTimerRunning(), hasTimerEnded(), setState(), almostEqual(), enums, etc.
}


    public boolean isTimerRunning() {
        return timer.isScheduled() && !timer.passed();
    }

    public boolean hasTimerEnded() {
        return timer.isScheduled() && timer.passed();
    }

    public void setState(State state, long time) {
        this.state = state;
        if (time == 0) {
            timer.reset();
            return;
        }
        timer.schedule(time);
    }

    public void setState(ReturnState state, long time) {
        this.returnState = state;
        if (time == 0) {
            timer.reset();
            return;
        }
        timer.schedule(time);
    }

    private static boolean almostEqual(float a, float b, float epsilon) {
        return Math.abs(a - b) < epsilon;
    }

    enum MainState {
        NONE,
        SWAP_N_START,
        RETURN
    }

    // bleh, its only for the tracker basically
    enum State {
        SWAPPING,
        WAITING_FOR_SWAP,
        ANALYZING, // :nerd:
        SETTING_SPAWN,
        WAITING_FOR_SPAWN,
        TOGGLING_PEST_DESTROYER,
        WAITING_FOR_PEST_DESTROYER,
        WAITING_FOR_WARP,
        ENDING
    }

    // rearranging this will break it because we're using .ordinal() in shouldCheckForFailsafes() to keep things simple
    enum ReturnState {
        STARTING,
        WAITING_FOR_SPAWN,
        TP_TO_SPAWN_PLOT,
        TP_VERIFY,
        VERIFY_PLOT,
        ESCAPE_TP,
        ESCAPE_TP_VERIFY,
        HOLD_AND_USE_MOUSEMAT,
        WAITING_FOR_MOUSEMAT,
        FLY_TO_ABOVE_SPAWN,
        WAITING_FOR_FLIGHT,
        FLY_TO_SPAWN_BLOCK,
        WAITING_FOR_FLIGHT_AND_VERIFYING,
        SNEAKING_AND_ROTATING,
        SETTING_SPAWN,
        WAITING_FOR_SPAWN_2, // definitely could've improved but you dont see me care now do you
        ENDING
    }
}
