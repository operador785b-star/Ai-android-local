package com.ialocal.android.inference;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class NativeBridge {
    static { System.loadLibrary("ialocal"); }

    private native int inspectModel(String path);

    private native long openSession();
    private native void closeSession(long handle);
    private native int generateCached(long handle, long conversation, int batch, boolean checkpoint,
                                     String path, byte[][] prompts, int context, int output, int threads,
                                     int profile, int seed, int timeout, long[] metrics, Callback callback);

    private native int generate(String path, byte[][] prompts, int context, int output,
                                int threads, int profile, int seed, int firstTokenTimeoutMs,
                                long[] metrics, Callback callback);

    private int generate(String path, byte[][] prompts, int context, int output, int threads,
                         int profile, int seed, long[] metrics, Callback callback) {
        return generate(path, prompts, context, output, threads, profile, seed, 120_000, metrics, callback);
    }

    private int generate(String path, byte[][] prompts, int context, int output, int threads, Callback callback) {
        return generate(path, prompts, context, output, threads, 1, -1, new long[7], callback);
    }

    public static final class Callback {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final boolean stopAfterFirstPiece;
        final List<Integer> phases = new ArrayList<>();
        boolean cancelDuringPrefill;
        int prefillChecks;
        int fragments;
        long firstPieceNs;
        long lastPieceNs;

        Callback(boolean stopAfterFirstPiece) { this.stopAfterFirstPiece = stopAfterFirstPiece; }
        public boolean isActive() {
            if (cancelDuringPrefill && !phases.isEmpty() && phases.get(phases.size() - 1) == 3) {
                return ++prefillChecks == 1;
            }
            return !stopAfterFirstPiece || fragments == 0;
        }
        public void onBytes(byte[] bytes) {
            lastPieceNs = System.nanoTime();
            if (fragments == 0) firstPieceNs = lastPieceNs;
            output.writeBytes(bytes);
            fragments++;
        }
        public void onPhase(int phase) { phases.add(phase); }
    }

    private static byte[] prompt(String user) {
        return ("<|im_start|>system\nResponde en español.<|im_end|>\n"
                + "<|im_start|>user\n" + user + "<|im_end|>\n"
                + "<|im_start|>assistant\n<think>\n\n</think>\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private void series(String[] args) throws Exception {
        String model = args[1];
        int profile = Integer.parseInt(args[2]);
        int context = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        int batch = Integer.parseInt(args[5]);
        String mode = args[6];
        if (inspectModel(model) != profile) throw new IllegalArgumentException("Perfil GGUF incompatible");
        if (!List.of("baseline", "cold", "warm").contains(mode)) throw new IllegalArgumentException(mode);
        long session = mode.equals("warm") ? openSession() : 0;
        if (mode.equals("warm") && session == 0) throw new OutOfMemoryError("Sesión JNI");
        try {
            for (String line : Files.readAllLines(Path.of(args[7]))) {
                String[] fields = line.split("\t");
                long conversation = Long.parseLong(fields[0]);
                int maxTokens = Integer.parseInt(fields[1]);
                byte[][] prompts = { Files.readAllBytes(Path.of(fields[2])) };
                var callback = new Callback(false);
                long[] metrics = new long[mode.equals("baseline") ? 8 : 19];
                int code;
                if (mode.equals("baseline")) {
                    code = generate(model, prompts, context, maxTokens, threads, profile, 1234, metrics, callback);
                } else {
                    long active = session != 0 ? session : openSession();
                    try {
                        code = generateCached(active, conversation, batch, session != 0, model, prompts,
                                context, maxTokens, threads, profile, 1234, 120_000, metrics, callback);
                    } finally {
                        if (session == 0) closeSession(active);
                    }
                }
                String answer = Base64.getEncoder().encodeToString(callback.output.toByteArray());
                System.out.printf("IA_RESULT\t%d\t%s\t%d\t%d\t%s%n", code, Arrays.toString(metrics),
                        callback.fragments, (callback.lastPieceNs - callback.firstPieceNs) / 1000, answer);
            }
        } finally {
            if (session != 0) closeSession(session);
        }
    }

    private void checkCache(String[] args) throws Exception {
        String file = args[1];
        int profile = Integer.parseInt(args[2]);
        byte[][] first = { Files.readAllBytes(Path.of(args[3])) };
        byte[][] next = { Files.readAllBytes(Path.of(args[4])) };
        long session = openSession();
        if (session == 0) throw new OutOfMemoryError("Sesión JNI");
        long[] metrics = new long[19];
        try {
            var cold = new Callback(false);
            int reference = generate(file, next, 2048, 16, 2, profile, 1234, metrics, cold);
            var start = new Callback(false);
            int started = generateCached(session, 100, 64, true, file, first, 2048, 16, 2,
                    profile, 1234, 120_000, metrics, start);
            if (started > 1 || metrics[14] != 0 || start.fragments == 0) throw new AssertionError("Sesión fría incompleta");
            long originalPrompt = metrics[0];
            var resumed = new Callback(false);
            int warm = generateCached(session, 100, 64, true, file, next, 2048, 16, 2,
                    profile, 1234, 120_000, metrics, resumed);
            if (reference > 1 || warm > 1 || metrics[12] <= 0 || metrics[12] >= originalPrompt ||
                    !Arrays.equals(cold.output.toByteArray(), resumed.output.toByteArray())) {
                throw new AssertionError("Restauración de prefijo incorrecta: " + Arrays.toString(metrics));
            }
            System.out.println("Prefijo restaurado: " + metrics[12] + " tokens");

            for (long chat = 101; chat <= 108; chat++) {
                var turn = new Callback(false);
                int code = generateCached(session, chat, 64, true, file, next, 2048, 16, 2,
                        profile, 1234, 120_000, metrics, turn);
                if (code > 1 || metrics[12] != 0 || metrics[14] != 1 || metrics[15] != 1 ||
                        !Arrays.equals(cold.output.toByteArray(), turn.output.toByteArray())) {
                    throw new AssertionError("Cambio de chat incorrecto: " + Arrays.toString(metrics));
                }
            }
            int stopped = generateCached(session, 108, 64, true, file, next, 2048, 16, 2,
                    profile, 1234, 120_000, metrics, new Callback(true));
            var recovered = new Callback(false);
            int code = generateCached(session, 108, 64, true, file, next, 2048, 16, 2,
                    profile, 1234, 120_000, metrics, recovered);
            if (stopped != 2 || code > 1 || metrics[12] != 0 || metrics[14] != 1 || metrics[15] != 0 ||
                    !Arrays.equals(cold.output.toByteArray(), recovered.output.toByteArray())) {
                throw new AssertionError("Recuperación tras detener incorrecta: " + Arrays.toString(metrics));
            }
            System.out.println("Ocho cambios de chat, cancelación y nueva respuesta verificados");
        } finally {
            closeSession(session);
        }
    }

    public static void main(String[] args) throws Exception {
        NativeBridge bridge = new NativeBridge();
        if (args.length == 8 && args[0].equals("--series")) {
            bridge.series(args);
            return;
        }
        if (args.length == 5 && args[0].equals("--cache-check")) {
            bridge.checkCache(args);
            return;
        }
        if (args.length != 0 && args[0].equals("--inspect")) {
            for (int i = 1; i < args.length; i++) {
                System.out.println(bridge.inspectModel(args[i]) + " " + args[i]);
            }
            return;
        }
        if (args.length == 3 && args[0].equals("--nanbeige-start")) {
            String file = args[1];
            byte[][] prompts = { java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[2])) };
            long[] metrics = new long[8];
            var slow = new Callback(false);
            int timedOut = bridge.generate(file, prompts, 2048, 40, 2, 3, 1234, 1, metrics, slow);
            if (timedOut != 7 || slow.fragments != 0 || metrics[3] != -1) {
                throw new AssertionError("Falló el límite antes del primer fragmento " + timedOut);
            }

            var cancelled = new Callback(false);
            cancelled.cancelDuringPrefill = true;
            int stopped = bridge.generate(file, prompts, 2048, 40, 2, 3, 1234, metrics, cancelled);
            if (stopped != 2 || cancelled.fragments != 0 || !cancelled.phases.equals(List.of(1, 2, 3))) {
                throw new AssertionError("Falló cancelación antes del primer fragmento " + stopped);
            }

            var response = new Callback(false);
            int status = bridge.generate(file, prompts, 2048, 40, 2, 3, 1234, metrics, response);
            if ((status != 0 && status != 1) || !response.phases.equals(List.of(1, 2, 3, 4)) ||
                metrics[7] != 512 || response.output.toString(StandardCharsets.UTF_8).isBlank()) {
                throw new AssertionError("Falló el inicio de Nanbeige " + status);
            }
            System.out.println(response.output.toString(StandardCharsets.UTF_8));
            System.out.println("IA_METRICS=" + java.util.Arrays.toString(metrics));
            System.out.println("Fases, cancelación durante el prefill y límite inicial verificados");
            return;
        }
        if (args.length == 3 && args[0].equals("--stability")) {
            int profile = Integer.parseInt(args[2]);
            if (bridge.inspectModel(args[1]) != profile) throw new IllegalArgumentException("Perfil GGUF incompatible");
            String prefix = profile == 2 ? "<think></think>" : "<think>\n\n</think>\n\n";
            byte[][] prompts = { ("<|im_start|>user\nEscribe tres palabras en español.<|im_end|>\n"
                    + "<|im_start|>assistant\n" + prefix).getBytes(StandardCharsets.UTF_8) };
            long[] metrics = new long[7];
            var cancelled = new Callback(true);
            int stopped = bridge.generate(args[1], prompts, 2048, 40, 2, profile, 1234, metrics, cancelled);
            if (stopped != 2 || cancelled.fragments != 1) throw new AssertionError("Falló cancelación " + stopped);
            var resumed = new Callback(false);
            int second = bridge.generate(args[1], prompts, 2048, 40, 2, profile, 1234, metrics, resumed);
            if ((second != 0 && second != 1) || resumed.fragments < 1) throw new AssertionError("Falló reinicio " + second);
            System.out.println("Cancelación y nueva inferencia verificadas");
            return;
        }
        if (args.length == 6 && args[0].equals("--eval")) {
            var prompt = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[3]));
            int profile = Integer.parseInt(args[2]);
            if (bridge.inspectModel(args[1]) != profile) throw new IllegalArgumentException("Perfil GGUF incompatible");
            var capture = new Callback(false);
            var metrics = new long[7];
            int status = bridge.generate(args[1], new byte[][] {prompt}, 2048,
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), profile, 1234, metrics, capture);
            System.out.write(capture.output.toByteArray());
            System.err.println("IA_METRICS=" + java.util.Arrays.toString(metrics));
            if (status != 0 && status != 1) System.exit(status);
            return;
        }
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
