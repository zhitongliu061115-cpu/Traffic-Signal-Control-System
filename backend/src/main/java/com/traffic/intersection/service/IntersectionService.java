package com.traffic.intersection.service;

import com.traffic.common.exception.BusinessException;
import com.traffic.intersection.dto.IntersectionResponse;
import com.traffic.intersection.repository.IntersectionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntersectionService {

    private final IntersectionRepository intersectionRepository;

    public IntersectionService(IntersectionRepository intersectionRepository) {
        this.intersectionRepository = intersectionRepository;
    }

    public List<IntersectionResponse> findAll() {
        return intersectionRepository.findAll();
    }

    public IntersectionResponse findByCode(String code) {
        return intersectionRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("intersection not found: " + code));
    }

    @Transactional
    public IntersectionResponse updateStatus(String code, String status) {
        int updatedRows = intersectionRepository.updateStatus(code, status);
        if (updatedRows == 0) {
            throw new BusinessException("intersection not found: " + code);
        }
        return findByCode(code);
    }
}
