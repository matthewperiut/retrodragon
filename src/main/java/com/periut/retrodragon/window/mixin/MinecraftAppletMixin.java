package com.periut.retrodragon.window.mixin;

import com.periut.retrodragon.window.BrnoMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MinecraftApplet;
import net.minecraft.client.util.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.swing.*;
import java.applet.Applet;
import java.awt.*;

@SuppressWarnings({"removal", "StringBufferReplaceableByString"})
@Mixin(MinecraftApplet.class)
public class MinecraftAppletMixin extends Applet {

    @Shadow
    private Minecraft minecraft;

    /**
     * @author Proudly overwritten by DanyGames2014
     * @reason because i don't give a shit
     */
    @Overwrite(remap = false)
    public void init() {
        // RetroCenter: child instances never show their applet frame -- hide
        // it immediately (init runs on the AWT thread, so this is safe)
        // instead of waiting for the invokeLater at the end of init.
        if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance()) {
            try {
                hideThemAll(this.getParent().getParent().getParent());
                hideThemAll(this.getParent().getParent());
                hideThemAll(this.getParent());
                hideThemAll(this);
            } catch (Exception ignored) {
            }
        }

        // PrismLauncher Window Size
        if (System.getProperty("org.prismlauncher.window.dimensions") != null) {
            String[] dimensions = System.getProperty("org.prismlauncher.window.dimensions").split("x");
            if (dimensions.length == 2) {
                try {
                    int prismWidth = Integer.parseInt(dimensions[0]);
                    int prismHeight = Integer.parseInt(dimensions[1]);
                    this.setSize(prismWidth, prismHeight);
                } catch (NumberFormatException ignored) {

                }
            }
        }

        boolean fullscreen = false;
        if (this.getParameter("fullscreen") != null) {
            fullscreen = this.getParameter("fullscreen").equalsIgnoreCase("true");
        }

        this.minecraft = new BrnoMinecraft(this.getWidth(), this.getHeight(), fullscreen);

        this.minecraft.hostAddress = this.getDocumentBase().getHost();
        if (this.getDocumentBase().getPort() > 0) {
            StringBuilder hostAdressBuilder = new StringBuilder();
            Minecraft mc = this.minecraft;
            mc.hostAddress = hostAdressBuilder.append(mc.hostAddress).append(":").append(this.getDocumentBase().getPort()).toString();
        }

        if (this.getParameter("username") != null && this.getParameter("sessionid") != null) {
            this.minecraft.session = new Session(this.getParameter("username"), this.getParameter("sessionid"));
            System.out.println("Setting user: " + this.minecraft.session.username);
            if (this.getParameter("mppass") != null) {
                this.minecraft.session.mpPass = this.getParameter("mppass");
            }
        } else {
            this.minecraft.session = new Session("Player" + System.currentTimeMillis() % 10000, "");
        }

        if (this.getParameter("server") != null && this.getParameter("port") != null) {
            this.minecraft.setStartupServer(this.getParameter("server"), Integer.parseInt(this.getParameter("port")));
        }

        // RetroCenter: a child instance reuses the hub's authenticated
        // session (retroauth re-derives its auth state from the raw
        // sessionId string) and autoconnects to its assigned server.
        com.periut.retrodragon.retrocenter.bridge.ChildConfig retrocenterConfig =
                com.periut.retrodragon.retrocenter.bridge.HubBridge.childConfig();
        if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance() && retrocenterConfig != null) {
            this.minecraft.session = new Session(retrocenterConfig.username, retrocenterConfig.sessionId);
            this.minecraft.setStartupServer(retrocenterConfig.host, retrocenterConfig.port);
            System.out.println("[RetroCenter] child session + autoconnect configured for "
                    + retrocenterConfig.host + ":" + retrocenterConfig.port);
        }

        this.startThread();

        SwingUtilities.invokeLater(() -> {
            try {
                hideThemAll(this.getParent().getParent().getParent());
                hideThemAll(this.getParent().getParent());
                hideThemAll(this.getParent());
                hideThemAll(this);
            } catch (Exception ignored) {
                // Ignore AWT/GLFW interaction errors
            }
        });
    }

    @Unique
    private void hideThemAll(Container container) {
        try {
            if (container instanceof Frame) {
                ((Frame) container).dispose();
            }
            for (Component component : container.getComponents()) {
                component.setVisible(false);
            }
        } catch (Exception ignored) {
            // Ignore any AWT/GLFW interaction errors
        }
    }

    /**
     * Run the game loop on a dedicated thread (like vanilla did) instead of
     * inline. The applet path dispatches init on the JVM-wide AWT
     * EventQueue; running the loop inline would occupy that queue forever,
     * which both starves any in-process child instance's init AND couples
     * unrelated AWT users to the game loop.
     *
     * @author DanyGames2014, matthewperiut
     * @reason de-AWT + RetroCenter multi-instance support
     */
    @Overwrite(remap = false)
    public void startThread() { // startMainThread
        Thread thread = new Thread(() -> this.minecraft.run(), "Minecraft main thread");
        thread.setUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance()) {
                // The hub is parked waiting on this child -- a dead game
                // thread must hand the window back, not hang the JVM.
                java.io.StringWriter stack = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(stack));
                com.periut.retrodragon.retrocenter.bridge.HubBridge.noteChildCrash(stack.toString());
                com.periut.retrodragon.retrocenter.bridge.HubBridge.childGameEnded("Child instance crashed: " + e);
            }
        });
        thread.start();
    }

    @Inject(method = "destroy", at = @At(value = "HEAD"), remap = false, cancellable = true)
    public void destroy(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "stopThread", at = @At(value = "HEAD"), remap = false, cancellable = true)
    public void stopThread(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "clearMemory", at = @At(value = "HEAD"), remap = false, cancellable = true)
    public void clearMemory(CallbackInfo ci) {
        ci.cancel();
    }
}
