package com.ialocal.android.inference;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class NativeBridge {
    static { System.loadLibrary("ialocal"); }

    private native int generate(String path, byte[][] prompts, int context, int output,
                                int threads, Callback callback);

    public static final class Callback {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final boolean stopAfterFirstPiece;
        int fragments;

        Callback(boolean stopAfterFirstPiece) { this.stopAfterFirstPiece = stopAfterFirstPiece; }
        public boolean isActive() { return !stopAfterFirstPiece || fragments == 0; }
        public void onBytes(byte[] bytes) { output.writeBytes(bytes); fragments++; }
    }

    private static byte[] prompt(String user) {
        return ("<|im_start|>system\nResponde en español.<|im_end|>\n"
                + "<|im_start|>user\n" + user + "<|im_end|>\n"
                + "<|im_start|>assistant\n<think>\n\n</think>\n\n").getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        NativeBridge bridge = new NativeBridge();
        Callback ongoing = new Callback(false);
        byte[][] prompts = {
            prompt("palabra ".repeat(1500)),
            prompt("Responde en una oración: ¿cuánto es seis por siete?")
        };
        int status = bridge.generate(args[0], prompts, 1024, 48, 2, ongoing);
        String answer = ongoing.output.toString(StandardCharsets.UTF_8).trim();
        if ((status != 0 && status != 1) || ongoing.fragments < 2 ||
            !answer.matches("(?is).*(42|cuarenta y dos).*")) {
            throw new AssertionError("Generación inesperada (" + status + "): " + answer);
        }
        System.out.println("Respuesta (" + ongoing.fragments + " fragmentos): " + answer);

        Callback cancelled = new Callback(true);
        int stop = bridge.generate(args[0], new byte[][] {prompts[1]}, 1024, 48, 2, cancelled);
        if (stop != 2 || cancelled.fragments != 1) throw new AssertionError("Falló la cancelación: " + stop);

        int oversize = bridge.generate(args[0], new byte[][] {prompts[0]}, 1024, 48, 2, new Callback(false));
        if (oversize != 4) throw new AssertionError("Falló el límite de contexto: " + oversize);

        Callback coding = new Callback(false);
        int codeStatus = bridge.generate(args[0], new byte[][] {
            prompt("Escribe una función Python llamada doble(n) que devuelve n * 2. Usa un bloque de código.")
        }, 1024, 96, 2, coding);
        String code = coding.output.toString(StandardCharsets.UTF_8).trim();
        if ((codeStatus != 0 && codeStatus != 1) || !code.contains("def doble")) {
            throw new AssertionError("Código inesperado (" + codeStatus + "): " + code);
        }
        System.out.println("Código:\n" + code);
        System.out.println("Límite de contexto y cancelación verificados.");
    }
}
