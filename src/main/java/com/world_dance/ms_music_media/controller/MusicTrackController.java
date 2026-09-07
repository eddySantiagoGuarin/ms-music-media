package com.world_dance.ms_music_media.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.world_dance.ms_music_media.service.MusicTrackService;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.MusicTrackResponseDto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * Controlador REST encargado de gestionar los endpoints relacionados con las pistas musicales.
 * Proporciona servicios para subir o actualizar audios asociados a inscripciones,
 * así como consultar metadatos y descargar o transmitir archivos multimedia.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/music")
public class MusicTrackController {

    private final MusicTrackService musicTrackService;

    /**
     * Sube o reemplaza la pista musical asociada a una inscripción de un participante.
     * Realiza las validaciones de identidad del usuario autenticado, formato de archivo,
     * existencia y estado APROBADA de la inscripción en ms-enrollment, y que el participante
     * sea el propietario de dicha inscripción. Almacena el binario en GridFS y los metadatos en MongoDB.
     *
     * @param authenticatedUserId id del usuario autenticado proveniente del encabezado X-User-Id
     * @param enrollmentId        id de la inscripción asociada a la pista musical
     * @param file                archivo de audio multimedia enviado en formato multipart
     * @return respuesta global con los metadatos de la pista procesada y código de estado 201 CREATED
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<HttpGlobalResponse<?>> uploadTrack(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestParam("enrollmentId") 
            @NotNull(message = "El id de la inscripción es obligatorio") 
            @Positive(message = "El id de la inscripción debe ser un número positivo") Long enrollmentId,
            @RequestParam("file") 
            @NotNull(message = "El archivo de audio no puede estar vacío.") MultipartFile file) {

        try {
            HttpGlobalResponse<MusicTrackResponseDto> response = musicTrackService.uploadOrUpdateTrack(enrollmentId, file, authenticatedUserId);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            HttpGlobalResponse<MusicTrackResponseDto> errorResponse = new HttpGlobalResponse<>();
            errorResponse.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Transmite o descarga el archivo binario almacenado en GridFS mediante el id de inscripción.
     * Verifica que el usuario autenticado tenga permisos de lectura (participante propietario,
     * organizador del evento, o rol STAFF/ADMIN en el evento). Retorna el recurso binario preparado
     * para streaming o descarga directa.
     *
     * @param enrollmentId        id de la inscripción consultada
     * @param authenticatedUserId id del usuario autenticado proveniente del encabezado X-User-Id
     * @return respuesta HTTP con el recurso binario y el encabezado Content-Disposition inline
     */
    @GetMapping("/download/{enrollmentId}")
    public ResponseEntity<?> downloadTrack(
            @PathVariable 
            @NotNull(message = "El id de la inscripción es obligatorio") 
            @Positive(message = "El id de la inscripción debe ser un número positivo") Long enrollmentId,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {
        try {
            Resource resource = musicTrackService.downloadTrack(enrollmentId, authenticatedUserId);
            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (Exception e) {
            HttpGlobalResponse<Object> errorResponse = new HttpGlobalResponse<>();
            errorResponse.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Obtiene la información estructurada de los metadatos de una pista musical sin descargar el archivo binario.
     * Verifica que el usuario autenticado tenga los permisos correspondientes para consultar la información de la pista.
     *
     * @param enrollmentId        id de la inscripción consultada
     * @param authenticatedUserId id del usuario autenticado proveniente del encabezado X-User-Id
     * @return respuesta global envuelta con el DTO de respuesta MusicTrackResponseDto
     */
    @GetMapping("/metadata/{enrollmentId}")
    public ResponseEntity<HttpGlobalResponse<?>> getMetadata(
            @PathVariable 
            @NotNull(message = "El id de la inscripción es obligatorio") 
            @Positive(message = "El id de la inscripción debe ser un número positivo") Long enrollmentId,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {
        try {
            HttpGlobalResponse<MusicTrackResponseDto> response = musicTrackService.getTrackMetadata(enrollmentId, authenticatedUserId);
            return ResponseEntity.status(HttpStatus.OK).body(response);
        } catch (Exception e) {
            HttpGlobalResponse<Object> errorResponse = new HttpGlobalResponse<>();
            errorResponse.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }
}