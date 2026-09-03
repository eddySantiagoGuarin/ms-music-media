package com.world_dance.ms_music_media.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.MusicTrackResponseDto;

/**
 * Interfaz del servicio encargada de definir la lógica de negocio para la gestión de pistas musicales.
 * Incluye operaciones para procesar subidas y reemplazos de archivos de audio, consultas de metadatos
 * y transmisión o descarga de archivos binarios almacenados en GridFS.
 */
public interface MusicTrackService {
    
    /**
     * Sube o actualiza la pista musical asociada a una inscripción.
     * Verifica la validez del archivo, existencia de la inscripción en ms-enrollment,
     * estado APROBADA de la misma y propiedad del participante sobre la inscripción.
     *
     * @param enrollmentId        id de la inscripción a la cual pertenece la pista
     * @param file                archivo de audio subido por el usuario
     * @param authenticatedUserId id del usuario autenticado enviado en los encabezados
     * @return respuesta global envuelta con los datos estructurados de la pista musical creada o actualizada
     */
    HttpGlobalResponse<MusicTrackResponseDto> uploadOrUpdateTrack(Long enrollmentId, MultipartFile file, Long authenticatedUserId);
    
    /**
     * Descarga o transmite el archivo binario de audio guardado en GridFS para la inscripción dada.
     * Verifica los permisos de acceso del usuario autenticado (participante propietario,
     * creador del evento o roles de administración STAFF/ADMIN).
     *
     * @param enrollmentId        id de la inscripción asociada
     * @param authenticatedUserId id del usuario autenticado
     * @return recurso de archivo multimedia listo para su reproducción o descarga
     */
    Resource downloadTrack(Long enrollmentId, Long authenticatedUserId);
    
    /**
     * Obtiene los metadatos e información técnica estructurada de la pista musical.
     * Verifica los permisos del usuario antes de retornar la información.
     *
     * @param enrollmentId        id de la inscripción a consultar
     * @param authenticatedUserId id del usuario autenticado
     * @return respuesta global envuelta con la información de metadatos de la pista
     */
    HttpGlobalResponse<MusicTrackResponseDto> getTrackMetadata(Long enrollmentId, Long authenticatedUserId);
}