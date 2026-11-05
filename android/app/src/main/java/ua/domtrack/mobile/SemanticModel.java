package ua.domtrack.mobile;

import ai.onnxruntime.*;
import android.content.Context;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Quantized multilingual MiniLM, evaluated entirely on this device. */
final class SemanticModel implements AutoCloseable {

    static final String VERSION = "multilingual-minilm-e8f8c211-qint8-v1";
    static final String MODEL_SHA =
        "783fea82d71a58179b830a4dbd2d58447e640609e98eedf9ffa12622d375a672";
    private static final String TOKEN_SHA =
        "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865";
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private long tokenizer;

    static {
        System.loadLibrary("domtrack_tokenizer");
    }

    private static native long nativeOpen(String path);

    private static native int[] nativeEncode(long handle, String text);

    private static native void nativeClose(long handle);

    SemanticModel(Context context) throws Exception {
        File root = new File(context.getNoBackupFilesDir(), "semantic");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException(
            "Недоступно хранилище модели"
        );
        File vocabulary = unpack(context, root, "sentencepiece.bpe.model", TOKEN_SHA);
        File model = unpack(context, root, "minilm-qint8.onnx", MODEL_SHA);
        tokenizer = nativeOpen(vocabulary.getAbsolutePath());
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(model.getAbsolutePath(), options);
        } catch (Exception e) {
            nativeClose(tokenizer);
            tokenizer = 0;
            throw e;
        }
    }

    private static File unpack(Context context, File root, String name, String sha)
        throws Exception {
        File file = new File(root, name);
        if (
            file.isFile() && file.length() > 0 && new File(root, name + ".verified-" + sha).isFile()
        ) return file;
        File part = new File(root, name + ".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (
            InputStream in = context.getAssets().open("ai/" + name);
            OutputStream out = new FileOutputStream(part)
        ) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(sha)) {
            part.delete();
            throw new IOException("Не прошла проверка модели ИИ");
        }
        if (!part.renameTo(file)) throw new IOException("Не удалось сохранить модель ИИ");
        try (
            OutputStream marker = new FileOutputStream(new File(root, name + ".verified-" + sha))
        ) {
            marker.write(sha.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    synchronized long[] tokens(String text) {
        if (tokenizer == 0) throw new IllegalStateException("Модель закрыта");
        int[] raw = nativeEncode(tokenizer, text);
        int length = Math.min(126, raw.length);
        long[] ids = new long[length + 2];
        ids[0] = 0;
        ids[ids.length - 1] = 2;
        // XLM-R vocabulary has a one-token fairseq offset; unknown maps to ID 3.
        for (int i = 0; i < length; i++) ids[i + 1] = raw[i] == 0 ? 3 : raw[i] + 1;
        return ids;
    }

    synchronized float[] embed(String text) throws Exception {
        long[] ids = tokens(text),
            mask = new long[ids.length],
            type = new long[ids.length];
        Arrays.fill(mask, 1);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put(
                "input_ids",
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] { 1, ids.length })
            );
            inputs.put(
                "attention_mask",
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[] { 1, ids.length })
            );
            if (session.getInputNames().contains("token_type_ids")) inputs.put(
                "token_type_ids",
                OnnxTensor.createTensor(env, LongBuffer.wrap(type), new long[] { 1, ids.length })
            );
            try (OrtSession.Result output = session.run(inputs)) {
                Object value = output.get(0).getValue();
                float[] pooled;
                if (value instanceof float[][][] token) {
                    pooled = new float[token[0][0].length];
                    for (float[] row : token[0])
                        for (int i = 0; i < pooled.length; i++) pooled[i] +=
                            row[i] / token[0].length;
                } else if (value instanceof float[][] sentence) pooled = sentence[0].clone();
                else throw new IOException("Неподдерживаемый ответ модели ИИ");
                if (pooled.length != 384) throw new IOException("Неверная размерность модели ИИ");
                double norm = 0;
                for (float f : pooled) norm += f * f;
                norm = Math.sqrt(norm);
                if (!Double.isFinite(norm) || norm < 1e-8) throw new IOException(
                    "Недостоверный ответ модели ИИ"
                );
                for (int i = 0; i < pooled.length; i++) pooled[i] /= (float) norm;
                return pooled;
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    static double cosine(float[] a, float[] b) {
        double sum = 0;
        if (a.length != b.length) return 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    public synchronized void close() throws OrtException {
        if (tokenizer != 0) {
            nativeClose(tokenizer);
            tokenizer = 0;
        }
        session.close();
    }
}
