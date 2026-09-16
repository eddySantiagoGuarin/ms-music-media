package com.world_dance.ms_music_media.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mongodb.client.gridfs.model.GridFSFile;
import com.mpatric.mp3agic.Mp3File;
import com.world_dance.ms_music_media.client.EnrollmentFeignClient;
import com.world_dance.ms_music_media.client.EventCategoryFeignClient;
import com.world_dance.ms_music_media.service.MusicTrackService;
import com.world_dance.wd_lib_common.dto.EnrollmentResponseDto;
import com.world_dance.wd_lib_common.dto.EventResponseDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.MusicTrackResponseDto;
import com.world_dance.wd_lib_common.dto.UserEventRoleResponseDto;
import com.world_dance.wd_lib_common.entity.MusicTrack;
import com.world_dance.wd_lib_common.enums.EnrollmentStatus;
import com.world_dance.wd_lib_common.enums.EventRole;
import com.world_dance.wd_lib_common.exception.BadRequestException;
import com.world_dance.wd_lib_common.repository.MusicTrackRepository;

import feign.FeignException;
import lombok.RequiredArgsConstructor;

/**
 * Servicio de implementación para la gestión de pistas musicales en la plataforma World Dance.
 * Administra la persistencia de binarios de audio en MongoDB GridFS, el registro de metadatos e historial
 * en documentos MongoDB, y la integración con ms-enrollment y ms-event-category para validar permisos.
 */
@Service
@RequiredArgsConstructor
public class MusicTrackServiceImpl implements MusicTrackService {

    private final MusicTrackRepository musicTrackRepository;
    private final GridFsTemplate gridFsTemplate;
    private final GridFsOperations gridFsOperations;
    private final EnrollmentFeignClient enrollmentFeignClient;
    private final EventCategoryFeignClient eventCategoryFeignClient;

    /**
     * Sube o reemplaza una pista musical en MongoDB y GridFS asociada a una inscripción.
     * Primero verifica la presencia del usuario autenticado y valida el formato del archivo de audio.
     * Consulta a ms-enrollment para confirmar la existencia de la inscripción, su estado APROBADA y
     * que pertenezca al usuario autenticado. Extrae metadatos de duración con mp3agic y guarda
     * el binario en GridFS. Si la pista ya existía, preserva la versión previa en la lista de historial.
     *
     * @param enrollmentId        id de la inscripción asociada
     * @param file                archivo multimedia a almacenar
     * @param authenticatedUserId id del usuario autenticado que realiza la petición
     * @return respuesta global envuelta con los metadatos de la pista procesada
     */
    @Override
    public HttpGlobalResponse<MusicTrackResponseDto> uploadOrUpdateTrack(Long enrollmentId, MultipartFile file, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            throw new SecurityException("No se pudo identificar al usuario autenticado.");
        }

        validateAudioFile(file);

        HttpGlobalResponse<MusicTrackResponseDto> response = new HttpGlobalResponse<>();

        // 1. Validar la inscripción
        EnrollmentResponseDto enrollment;
        try {
            enrollment = enrollmentFeignClient.getEnrollmentById(enrollmentId);
        } catch (FeignException e) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }

        if (enrollment == null) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }

        if (enrollment.getUserId() == null || !enrollment.getUserId().equals(authenticatedUserId)) {
            throw new SecurityException("Acceso denegado: Solo el participante dueño de la inscripción puede subir la pista musical.");
        }

        if (enrollment.getStatus() != EnrollmentStatus.APPROVED) {
            throw new BadRequestException("La inscripción no se encuentra en estado APROBADA.");
        }

        // 2. Extraer información del archivo de forma flexible
        double durationSeconds = extractAudioDuration(file);
        double sizeKb = file.getSize() / 1024.0;
        String format = getFileExtension(file.getOriginalFilename()).toUpperCase();

        // 3. Subir el archivo a GridFS
        ObjectId gridFsId;
        try {
            gridFsId = gridFsTemplate.store(
                    file.getInputStream(),
                    file.getOriginalFilename(),
                    file.getContentType()
            );
        } catch (Exception e) {
            throw new BadRequestException("Error al guardar el archivo multimedia en la base de datos.");
        }

        // 4. Guardar / Actualizar documento en MongoDB
        Optional<MusicTrack> existingTrackOpt = musicTrackRepository.findByEnrollmentId(enrollmentId);
        MusicTrack track;

        if (existingTrackOpt.isPresent()) {
            track = existingTrackOpt.get();

            MusicTrack.HistoryLog log = new MusicTrack.HistoryLog();
            log.setPreviousGridFsId(track.getGridFsId());
            log.setPreviousFilename(track.getFileMetadata() != null ? track.getFileMetadata().getFilename() : null);
            log.setReplacedAt(Instant.now());
            if (track.getHistory() == null) {
                track.setHistory(new ArrayList<>());
            }
            track.getHistory().add(log);

            track.setGridFsId(gridFsId.toHexString());
            if (track.getFileMetadata() == null) {
                track.setFileMetadata(new MusicTrack.FileMetadata());
            }
            track.getFileMetadata().setFilename(file.getOriginalFilename());
            track.getFileMetadata().setStorageUrl("/music/download/" + enrollmentId);
            track.getFileMetadata().setFormat(format);
            track.getFileMetadata().setSizeKb(sizeKb);
            track.getPlaybackConfig().setDurationSeconds(durationSeconds);
            track.getStatus().setUploadedAt(Instant.now());
        } else {
            track = new MusicTrack();
            track.setEnrollmentId(enrollmentId);
            track.setGridFsId(gridFsId.toHexString());

            MusicTrack.FileMetadata fileMetadata = new MusicTrack.FileMetadata();
            fileMetadata.setFilename(file.getOriginalFilename());
            fileMetadata.setStorageUrl("/music/download/" + enrollmentId);
            fileMetadata.setFormat(format);
            fileMetadata.setSizeKb(sizeKb);
            track.setFileMetadata(fileMetadata);

            MusicTrack.PlaybackConfig playbackConfig = new MusicTrack.PlaybackConfig();
            playbackConfig.setDurationSeconds(durationSeconds);
            playbackConfig.setVolumeNormalization(1.0);
            track.setPlaybackConfig(playbackConfig);

            MusicTrack.Status status = new MusicTrack.Status();
            status.setIsActive(true);
            status.setUploadedAt(Instant.now());
            track.setStatus(status);
        }

        musicTrackRepository.save(track);

        MusicTrackResponseDto data = mapToResponseDto(track);
        response.setData(data);
        response.setMessage("La pista musical fue procesada y guardada de manera exitosa.");

        return response;
    }

    /**
     * Obtiene y retorna el recurso binario de la pista musical almacenado en GridFS.
     * Ejecuta las comprobaciones de permisos para asegurar que el usuario solicitante sea el participante
     * propietario, el creador del evento o posea un rol administrativo (ADMIN/STAFF).
     *
     * @param enrollmentId        id de la inscripción
     * @param authenticatedUserId id del usuario autenticado
     * @return recurso de archivo multimedia apto para transmisión o descarga
     */
    @Override
    public Resource downloadTrack(Long enrollmentId, Long authenticatedUserId) {
        EnrollmentResponseDto enrollment;
        try {
            enrollment = enrollmentFeignClient.getEnrollmentById(enrollmentId);
        } catch (FeignException e) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }
        if (enrollment == null) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }

        validateReadPermission(enrollment, authenticatedUserId);

        MusicTrack track = musicTrackRepository.findByEnrollmentId(enrollmentId)
                .orElseThrow(() -> new BadRequestException("No se encontró una pista musical registrada para la inscripción: " + enrollmentId));

        if (!ObjectId.isValid(track.getGridFsId())) {
            throw new BadRequestException("El archivo de audio no existe en el almacenamiento GridFS.");
        }

        GridFSFile gridFSFile = gridFsTemplate.findOne(
            new Query(Criteria.where("_id").is(new ObjectId(track.getGridFsId()))));
        if (gridFSFile == null) {
            throw new BadRequestException("El archivo de audio no existe en el almacenamiento GridFS.");
        }

        return gridFsOperations.getResource(gridFSFile);
    }

    /**
     * Consulta y devuelve la información estructurada de metadatos de la pista musical.
     * Aplica la validaciones de autorización previas al retorno de los datos.
     *
     * @param enrollmentId        id de la inscripción a consultar
     * @param authenticatedUserId id del usuario autenticado
     * @return respuesta global envuelta con el DTO de metadatos de la pista
     */
    @Override
    public HttpGlobalResponse<MusicTrackResponseDto> getTrackMetadata(Long enrollmentId, Long authenticatedUserId) {
        EnrollmentResponseDto enrollment;
        try {
            enrollment = enrollmentFeignClient.getEnrollmentById(enrollmentId);
        } catch (FeignException e) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }
        if (enrollment == null) {
            throw new BadRequestException("Inscripción no encontrada con el id: " + enrollmentId);
        }

        validateReadPermission(enrollment, authenticatedUserId);

        HttpGlobalResponse<MusicTrackResponseDto> response = new HttpGlobalResponse<>();

        MusicTrack track = musicTrackRepository.findByEnrollmentId(enrollmentId)
            .orElseThrow(() -> new BadRequestException("No se encontraron metadatos para la inscripción: " + enrollmentId));

        MusicTrackResponseDto data = mapToResponseDto(track);
        response.setData(data);
        response.setMessage("Metadatos de la pista obtenidos con éxito.");

        return response;
    }

    /**
     * Valida si el usuario autenticado posee permisos de lectura/descarga sobre la pista musical.
     * Permite el acceso si el usuario es el participante de la inscripción, el owner del evento,
     * o posee un rol de ADMIN o STAFF asignado en el evento.
     *
     * @param enrollment          datos de la inscripción consultada
     * @param authenticatedUserId id del usuario autenticado
     */
    private void validateReadPermission(EnrollmentResponseDto enrollment, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            throw new SecurityException("No se pudo identificar al usuario autenticado.");
        }

        // 1. Es el participante dueño de la inscripción
        if (enrollment.getUserId() != null && enrollment.getUserId().equals(authenticatedUserId)) {
            return;
        }

        // 2. Creador / Organizador del evento
        if (enrollment.getEventId() != null) {
            try {
                HttpGlobalResponse<EventResponseDto> eventResponse = eventCategoryFeignClient.getEventById(enrollment.getEventId());
                EventResponseDto event = eventResponse != null ? eventResponse.getData() : null;
                if (event != null && event.getOwnerId() != null && event.getOwnerId().equals(authenticatedUserId)) {
                    return;
                }
            } catch (Exception ignored) {
            }

            // 3. Rol ADMIN o STAFF en el evento
            try {
                UserEventRoleResponseDto roleDto = enrollmentFeignClient.getUserEventRole(enrollment.getEventId(), authenticatedUserId);
                if (roleDto != null && roleDto.getRoleInEvent() != null) {
                    EventRole role = roleDto.getRoleInEvent();
                    if (role == EventRole.ADMIN || role == EventRole.STAFF) {
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        throw new SecurityException("Acceso denegado: No tienes permisos para acceder o descargar esta pista musical.");
    }

    /**
     * Valida que el archivo multimedia enviado no sea nulo, no esté vacío y contenga
     * una extensión o MIME type válido para archivos de audio.
     *
     * @param file archivo recibido en la petición HTTP
     */
    private void validateAudioFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("El archivo de audio no puede estar vacío.");
        }

        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();

        boolean hasValidExtension = filename != null && 
            filename.toLowerCase().matches(".*\\.(mp3|mpeg|mp4|m4a|wav|aac|ogg)$");

        boolean hasValidMime = contentType != null && 
            (contentType.toLowerCase().startsWith("audio/") || contentType.equalsIgnoreCase("video/mpeg"));

        if (!hasValidExtension && !hasValidMime) {
            throw new BadRequestException("El archivo debe ser un formato de audio válido (MP3, MPEG, WAV, M4A, etc.).");
        }
    }

    /**
     * Extrae la duración en segundos del archivo de audio utilizando la biblioteca mp3agic.
     * Si la lectura del contenedor o metadatos no es posible, asigna 0.0 segundos de forma tolerante.
     *
     * @param file archivo multimedia a procesar
     * @return duración estimada en segundos
     */
    private double extractAudioDuration(MultipartFile file) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("audio_", ".tmp");
            try (FileOutputStream os = new FileOutputStream(tempFile)) {
                os.write(file.getBytes());
            }
            
            Mp3File mp3file = new Mp3File(tempFile);
            return mp3file.getLengthInSeconds();
        } catch (Exception e) {
            // Si mp3agic falla por el contenedor o tipo de cabecera, no frena la subida
            return 0.0;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Obtiene la extensión del archivo a partir de su nombre original.
     *
     * @param filename nombre del archivo enviado
     * @return extensión extraída en texto o "AUDIO" si no posee extensión
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "AUDIO";
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    /**
     * Mapea un documento de entidad MusicTrack a su correspondiente DTO de respuesta MusicTrackResponseDto.
     *
     * @param track entidad persistida en MongoDB
     * @return DTO listo para ser serializado y devuelto al cliente
     */
    private MusicTrackResponseDto mapToResponseDto(MusicTrack track) {
        MusicTrackResponseDto dto = new MusicTrackResponseDto();
        dto.setId(track.getId());
        dto.setEnrollmentId(track.getEnrollmentId());
        if (track.getFileMetadata() != null) {
            dto.setFilename(track.getFileMetadata().getFilename());
            dto.setFormat(track.getFileMetadata().getFormat());
            dto.setSizeKb(track.getFileMetadata().getSizeKb());
        }
        if (track.getPlaybackConfig() != null) {
            dto.setDurationSeconds(track.getPlaybackConfig().getDurationSeconds());
        }
        if (track.getStatus() != null) {
            dto.setIsActive(track.getStatus().getIsActive());
            dto.setUploadedAt(track.getStatus().getUploadedAt());
        }
        if (track.getHistory() != null) {
            List<MusicTrackResponseDto.HistoryEntryDto> history = track.getHistory().stream()
                    .map(log -> MusicTrackResponseDto.HistoryEntryDto.builder()
                            .previousFilename(log.getPreviousFilename())
                            .replacedAt(log.getReplacedAt())
                            .build())
                    .collect(Collectors.toList());
            dto.setHistory(history);
        }
        return dto;
    }
}