package org.example.channel;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.example.channel.dto.MediaMtxPathConfig;
import org.example.channel.dto.MediaMtxPathList;

/**
 * MediaMTX yönetim API'si (v3).
 *
 * <p>İki ayrı kavram var, karıştırılmamalı:
 * <ul>
 *   <li>{@code /config/paths/...} — <b>yapılandırma</b>. Path'in tanımı;
 *       eklemek/silmek buradan yapılır.</li>
 *   <li>{@code /paths/...} — <b>çalışma zamanı durumu</b>. Yayının gerçekten
 *       akıp akmadığı, kaç izleyici olduğu buradan okunur.</li>
 * </ul>
 * Bir path'in yapılandırmada var olması yayının aktığı anlamına gelmez.
 */
@Path("/v3")
@RegisterRestClient(configKey = "mediamtx")
public interface
MediaMtxClient {

    @POST
    @Path("/config/paths/add/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    void addPath(@PathParam("name") String name, MediaMtxPathConfig config);

    @PATCH
    @Path("/config/paths/patch/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchPath(@PathParam("name") String name, MediaMtxPathConfig config);

    @DELETE
    @Path("/config/paths/delete/{name}")
    void deletePath(@PathParam("name") String name);

    /**
     * Tüm path'lerin çalışma zamanı durumu. Kanal listesinde her kanal için
     * ayrı istek atmak yerine tek çağrıyla hepsi alınıp eşleştirilir.
     */
    @GET
    @Path("/paths/list")
    @Produces(MediaType.APPLICATION_JSON)
    MediaMtxPathList listPaths(@QueryParam("itemsPerPage") int itemsPerPage);
}
