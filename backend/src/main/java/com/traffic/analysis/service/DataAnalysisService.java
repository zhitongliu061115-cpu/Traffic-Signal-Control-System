package com.traffic.analysis.service;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.repository.DataAnalysisRepository;
import org.springframework.stereotype.Service;

@Service
public class DataAnalysisService {

    private final DataAnalysisRepository dataAnalysisRepository;

    public DataAnalysisService(DataAnalysisRepository dataAnalysisRepository) {
        this.dataAnalysisRepository = dataAnalysisRepository;
    }

    public DataAnalysisBootstrapResponse loadBootstrapData() {
        return dataAnalysisRepository.loadBootstrapData();
    }
}
