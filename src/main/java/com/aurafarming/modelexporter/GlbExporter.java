// Adapted from the RuneProfile plugin (github.com/ReinhardtR/runeprofile-plugin),
// BSD 2-Clause, Copyright (c) 2022 Reinhardt Rijna — see THIRD_PARTY_NOTICES.md.
package com.aurafarming.modelexporter;

import com.google.gson.Gson;
import lombok.NonNull;
import net.runelite.api.Client;
import net.runelite.api.Model;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports a game {@link Model} as a binary glTF.
 */
public final class GlbExporter {
    private GlbExporter() {
    }

    /**
     * Writes a self contained GLB with every texture embedded. One file, opens
     * in any glTF viewer, no external requests.
     *
     * @param gson RuneLite's own injected instance — never construct a fresh one here,
     *             the Plugin Hub packager rejects that at build time.
     */
    public static byte[] toBytes(@NonNull Client client, @NonNull Model model,
                                 @NonNull String name, @NonNull Gson gson) throws IOException {
        return toBytes(client, model, new GlbWriter.Options().embedTextures().modelName(name), gson);
    }

    public static byte[] toBytes(@NonNull Client client, @NonNull Model model,
                                 @NonNull GlbWriter.Options options, @NonNull Gson gson) throws IOException {
        final MeshData mesh = ModelMeshBuilder.build(model);
        final GameTextures textures = new GameTextures(client);

        final Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("source", "aura-tracker-plugin");
        extras.put("faceCount", model.getFaceCount());
        extras.put("vertexCount", mesh.getVertexCount());
        options.extras(extras);

        return GlbWriter.write(mesh, textures::get, options, gson);
    }
}
