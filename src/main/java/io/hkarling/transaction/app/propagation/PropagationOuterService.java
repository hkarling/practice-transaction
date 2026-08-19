package io.hkarling.transaction.app.propagation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class PropagationOuterService {

  private final PropagationDemoService propagationDemoService;

  @Transactional
  public void requiredThenFail(String message) {
    propagationDemoService.logRequired(message);
    throw new RuntimeException("의도적 실패");
  }

  @Transactional
  public void requiresNewThenFail(String message) {
    propagationDemoService.logRequiresNew(message);
    throw new RuntimeException("의도적 실패");
  }

  @Transactional
  public void nestedThenFail(String message) {
    propagationDemoService.logNested(message);
    throw new RuntimeException("의도적 실패");
  }
}
