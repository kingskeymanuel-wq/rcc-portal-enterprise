package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.FavoriteProcedure;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.FavoriteProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class FavoriteProcedureService {

    private final FavoriteProcedureRepository favoriteRepository;
    private final ProcedureRepository procedureRepository;
    private final UserRepository userRepository;

    public FavoriteProcedureService(
            FavoriteProcedureRepository favoriteRepository,
            ProcedureRepository procedureRepository,
            UserRepository userRepository) {

        this.favoriteRepository = favoriteRepository;
        this.procedureRepository = procedureRepository;
        this.userRepository = userRepository;
    }

    public void addFavorite(Integer procedureId, AuthenticatedUser authenticatedUser) {

        User user = userRepository.findFirstByUsernameIgnoreCase(authenticatedUser.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        Procedure procedure = procedureRepository.findById(procedureId)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));

        if (favoriteRepository.existsByUserAndProcedure(user, procedure)) {
            return;
        }

        FavoriteProcedure favorite = FavoriteProcedure.builder()
                .user(user)
                .procedure(procedure)
                .build();

        favoriteRepository.save(favorite);
    }

    public void removeFavorite(Integer procedureId, AuthenticatedUser authenticatedUser) {

        User user = userRepository.findFirstByUsernameIgnoreCase(authenticatedUser.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        Procedure procedure = procedureRepository.findById(procedureId)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));

        favoriteRepository.deleteByUserAndProcedure(user, procedure);
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Integer procedureId, AuthenticatedUser authenticatedUser) {

        User user = userRepository.findFirstByUsernameIgnoreCase(authenticatedUser.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        Procedure procedure = procedureRepository.findById(procedureId)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));

        return favoriteRepository.existsByUserAndProcedure(user, procedure);
    }

    @Transactional(readOnly = true)
    public List<FavoriteProcedure> listFavorites(AuthenticatedUser authenticatedUser) {

        User user = userRepository.findFirstByUsernameIgnoreCase(authenticatedUser.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        return favoriteRepository.findByUserOrderByCreatedAtDesc(user);
    }

    @Transactional(readOnly = true)
    public long favoriteCount(Integer procedureId) {

        Procedure procedure = procedureRepository.findById(procedureId)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));

        return favoriteRepository.countByProcedure(procedure);
    }
}
