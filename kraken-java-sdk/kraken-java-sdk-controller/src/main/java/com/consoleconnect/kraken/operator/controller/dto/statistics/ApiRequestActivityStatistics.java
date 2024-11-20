package com.consoleconnect.kraken.operator.controller.dto.statistics;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiRequestActivityStatistics {
  private List<RequestStatistics> requestStatistics;
}