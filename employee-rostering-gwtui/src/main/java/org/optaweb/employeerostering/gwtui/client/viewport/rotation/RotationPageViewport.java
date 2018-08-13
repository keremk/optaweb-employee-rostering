/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaweb.employeerostering.gwtui.client.viewport.rotation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import org.jboss.errai.ioc.client.api.ManagedInstance;
import org.jboss.errai.ui.client.local.api.elemental2.IsElement;
import org.jboss.errai.ui.client.local.spi.TranslationService;
import org.jboss.errai.ui.shared.api.annotations.Templated;
import org.optaweb.employeerostering.gwtui.client.common.EventManager;
import org.optaweb.employeerostering.gwtui.client.common.FailureShownRestCallback;
import org.optaweb.employeerostering.gwtui.client.common.Lockable;
import org.optaweb.employeerostering.gwtui.client.notification.Notifications;
import org.optaweb.employeerostering.gwtui.client.resources.i18n.OptaWebUIConstants;
import org.optaweb.employeerostering.gwtui.client.tenant.TenantStore;
import org.optaweb.employeerostering.gwtui.client.util.DateTimeUtils;
import org.optaweb.employeerostering.gwtui.client.util.PromiseUtils;
import org.optaweb.employeerostering.gwtui.client.viewport.DateTimeViewport;
import org.optaweb.employeerostering.gwtui.client.viewport.grid.HasGridObjects;
import org.optaweb.employeerostering.gwtui.client.viewport.grid.Lane;
import org.optaweb.employeerostering.gwtui.client.viewport.grid.Lane.DummySublane;
import org.optaweb.employeerostering.gwtui.client.viewport.grid.LinearScale;
import org.optaweb.employeerostering.gwtui.client.viewport.impl.DynamicScale;
import org.optaweb.employeerostering.shared.common.HasTimeslot;
import org.optaweb.employeerostering.shared.employee.Employee;
import org.optaweb.employeerostering.shared.rotation.view.RotationView;
import org.optaweb.employeerostering.shared.rotation.view.ShiftTemplateView;
import org.optaweb.employeerostering.shared.shift.ShiftRestServiceBuilder;
import org.optaweb.employeerostering.shared.spot.Spot;

import static org.optaweb.employeerostering.gwtui.client.common.EventManager.Event.ROTATION_SAVE;

@Templated
public class RotationPageViewport extends DateTimeViewport<RotationView, RotationMetadata>
        implements
        IsElement {

    @Inject
    private RotationPageViewportBuilder viewportBuilder;
    @Inject
    private ManagedInstance<ShiftTemplateModel> shiftTemplateModelInstances;
    @Inject
    private TenantStore tenantStore;
    @Inject
    private EventManager eventManager;
    @Inject
    private PromiseUtils promiseUtils;
    @Inject
    private TranslationService translationService;
    @Inject
    private DateTimeUtils dateTimeUtils;
    @Inject
    private Notifications notifications;

    private Map<Long, Spot> spotIdToSpotMap;
    private Map<Long, Employee> employeeIdToEmployeeMap;

    public static final LocalDateTime BASE_DATE = HasTimeslot.EPOCH;

    @PostConstruct
    private void init() {
        viewportBuilder.withViewport(this);
        eventManager.subscribeToEventForever(ROTATION_SAVE, (v) -> saveRotation());
    }

    @Override
    protected void withView(RotationView view) {
        spotIdToSpotMap = super.getIdMapFor(view.getSpotList(), (s) -> s.getId());
        employeeIdToEmployeeMap = super.getIdMapFor(view.getEmployeeList(), (s) -> s.getId());
    }

    @Override
    protected LinearScale<LocalDateTime> getScaleFor(RotationView view) {
        return new DynamicScale(BASE_DATE,
                                BASE_DATE.plusDays(view.getRotationLength()),
                                Duration.ofHours(1));
    }

    @Override
    protected Map<Long, String> getLaneTitlesFor(RotationView view) {
        return view.getSpotList().stream().collect(Collectors.toMap((s) -> s.getId(), (s) -> s.getName()));
    }

    @Override
    protected RepeatingCommand getViewportBuilderCommand(RotationView view, Lockable<Map<Long, Lane<LocalDateTime, RotationMetadata>>> lockableLaneMap) {
        return viewportBuilder.getWorkerCommand(view, lockableLaneMap, System.currentTimeMillis());
    }

    @Override
    protected Function<LocalDateTime, HasGridObjects<LocalDateTime, RotationMetadata>> getInstanceCreator(RotationView view, Long laneId) {
        final Spot spot = spotIdToSpotMap.get(laneId);
        return (t) -> {
            ShiftTemplateView newInstance = new ShiftTemplateView();
            newInstance.setSpotId(spot.getId());
            newInstance.setTenantId(tenantStore.getCurrentTenantId());
            newInstance.setDurationBetweenRotationStartAndTemplateStart(
                    Duration.between(BASE_DATE, t));
            newInstance.setShiftTemplateDuration(Duration.ofHours(8));
            return shiftTemplateModelInstances.get().withShiftTemplateView(newInstance);
        };
    }

    @Override
    protected RotationMetadata getMetadata() {
        return new RotationMetadata(spotIdToSpotMap, employeeIdToEmployeeMap);
    }

    @Override
    protected Function<LocalDateTime, String> getDateHeaderFunction() {
        return (date) -> translationService.format(OptaWebUIConstants.Rotation_dateHeader, Duration.between(BASE_DATE, date).toDays() + 1);
    }

    @Override
    protected Function<LocalDateTime, String> getTimeHeaderFunction() {
        return (date) -> {
            return dateTimeUtils.translateLocalTime(date.toLocalTime());
        };
    }

    @Override
    protected Function<LocalDateTime, List<String>> getDateHeaderIconClassesFunction() {
        return (date) -> Collections.emptyList();
    }

    private void saveRotation() {
        getLockableLaneMap().acquire().then(laneMap -> {
            Map<Long, List<ShiftTemplateView>> newSpotIdToShiftTemplateViewListMap = new HashMap<>();

            for (Long spotId : laneMap.keySet()) {
                Lane<LocalDateTime, RotationMetadata> lane = laneMap.get(spotId);
                Collection<ShiftTemplateModel> shiftTemplateMap = lane.getGridObjects(ShiftTemplateModel.class);
                List<ShiftTemplateView> shiftTemplateList = new ArrayList<>();
                shiftTemplateMap.forEach((template) -> shiftTemplateList.add(template.getShiftTemplateView()));
                newSpotIdToShiftTemplateViewListMap.put(spotId, shiftTemplateList);
            }
            RotationView rotationView = new RotationView();
            rotationView.setTenantId(tenantStore.getCurrentTenantId());
            rotationView.setSpotIdToShiftTemplateViewListMap(newSpotIdToShiftTemplateViewListMap);
            ShiftRestServiceBuilder.updateRotation(tenantStore.getCurrentTenantId(), rotationView,
                                                   FailureShownRestCallback.onSuccess(e -> {
                                                       notifications.showSuccessMessage(OptaWebUIConstants.Notifications_rotationSaved);
                                                       viewportBuilder.buildRotationViewport(this);
                                                   }));
            return promiseUtils.resolve();
        });
    }

    @Override
    protected DummySublane getDummySublane() {
        return DummySublane.BOTTOM;
    }

    @Override
    protected String getLoadingTaskId() {
        return "rotation";
    }

    @Override
    protected boolean showLoadingSpinner() {
        return true;
    }

    @Override
    protected List<Long> getLaneOrder(RotationView view) {
        return view.getSpotList().stream().map(s -> s.getId()).collect(Collectors.toList());
    }
}
