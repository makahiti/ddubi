package com.ssafy.ddubi.domain.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.evaluation.BusPreviewClient;
import com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDepartureEvaluator;
import com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvaluationService;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleCommitWakeup;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationWorkflow;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import static org.assertj.core.api.Assertions.*;

/**
 * 추천 도메인 싱글턴들이 Spring 기동 시 생성자를 고를 수 있는지 컨텍스트 없이 확인한다.
 *
 * 생성자를 여러 개 두면서 주입 대상을 표시하지 않으면 Spring은 인자 없는 생성자를 찾다가 실패하고,
 * 그 순간 ApplicationContext 전체가 뜨지 않는다. 이 실패는 컴파일로도 단위 테스트로도 드러나지 않고
 * Docker가 필요한 통합 테스트에서만 보이므로(2026-09-18 CI 파이프라인 204184), 같은 판정 로직을
 * 직접 불러 로컬에서 먼저 막는다.
 */
class SpringConstructorSelectionTest {
    /** Spring이 실제로 쓰는 판정기다. 규칙을 테스트에 베껴 적으면 실제 동작과 어긋날 수 있다. */
    final AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();

    /** Spring이 이 타입에 쓸 생성자. 고르지 못하면 실패시킨다(인자 없는 생성자로 물러설 수도 없는 타입들이다). */
    Constructor<?> selected(Class<?> type) {
        Constructor<?>[] candidates = processor.determineCandidateConstructors(type, type.getSimpleName());
        if (candidates == null) {
            // 주입 후보가 없으면 Spring은 인자 없는 생성자로 물러선다. 그것도 없으면 기동이 실패한다.
            try { return type.getDeclaredConstructor(); }
            catch (NoSuchMethodException none) {
                return fail("%s: 주입 생성자를 고르지 못했고 인자 없는 생성자도 없다 → 기동 실패", type.getSimpleName());
            }
        }
        assertThat(candidates).as("%s: 주입 후보 생성자는 하나여야 한다", type.getSimpleName()).hasSize(1);
        return candidates[0];
    }

    @Test void busPreviewClientInjectsThePropertyBoundConstructorNotTheBatchSizeOne() {
        // 배치 크기 지정 생성자(테스트 전용)가 생기면서 public 생성자가 둘이 됐다. 주입 대상이 고정돼야 한다.
        assertThat(BusPreviewClient.class.getConstructors()).hasSize(2);
        assertThat(selected(BusPreviewClient.class).getParameterTypes())
                .containsExactly(ObjectMapper.class, String.class, int.class);
    }

    /** 표시 없는 다중 생성자 — 2026-09-18 기동 실패와 같은 모양이다. 검사기가 이 경우를 실제로 잡는지 확인한다. */
    static class TwoConstructorsWithoutMarker {
        public TwoConstructorsWithoutMarker(String a) { }
        public TwoConstructorsWithoutMarker(String a, int b) { }
    }

    @Test void theCheckActuallyCatchesUnmarkedMultipleConstructors() {
        assertThat(processor.determineCandidateConstructors(TwoConstructorsWithoutMarker.class, "sample")).isNull();
        assertThatThrownBy(() -> selected(TwoConstructorsWithoutMarker.class)).isInstanceOf(AssertionError.class);
    }

    @Test void changedRecommendationSingletonsAreInstantiableBySpring() {
        for (Class<?> type : List.of(BusPreviewClient.class, JourneyEvaluationService.class,
                ConditionalDepartureEvaluator.class, ScheduleEvaluationWorkflow.class, ScheduleCommitWakeup.class)) {
            assertThat(selected(type)).as("%s 주입 생성자", type.getSimpleName()).isNotNull();
        }
    }
}
