package escom.chris.storage.repository;

import escom.chris.storage.entity.SpectrogramEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpectrogramRepository extends JpaRepository<SpectrogramEntity, String> {

    List<SpectrogramEntity> findTop25ByOrderByCreatedAtDesc();
}

