package com.seatlock.event;

import com.seatlock.auth.AuthUser;
import com.seatlock.event.EventDtos.CreateEventRequest;
import com.seatlock.event.EventDtos.EventSummary;
import com.seatlock.event.EventDtos.HoldResponse;
import com.seatlock.event.EventDtos.ReleaseResponse;
import com.seatlock.event.EventDtos.SeatMapResponse;
import com.seatlock.event.EventDtos.SeatSelection;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.hold.SeatHoldService.HoldResult;
import com.seatlock.realtime.SeatBroadcaster;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;
    private final SeatHoldService holdService;
    private final SeatBroadcaster broadcaster;

    public EventController(EventService eventService, SeatHoldService holdService, SeatBroadcaster broadcaster) {
        this.eventService = eventService;
        this.holdService = holdService;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public List<EventSummary> list() {
        return eventService.list();
    }

    @GetMapping("/{id}")
    public EventSummary get(@PathVariable("id") Long id) {
        return eventService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventSummary create(@Valid @RequestBody CreateEventRequest request,
                               @AuthenticationPrincipal AuthUser user) {
        return eventService.create(request, user.id());
    }

    /** Public, but a signed-in viewer also sees which seats they are holding. */
    @GetMapping("/{id}/seats")
    public SeatMapResponse seats(@PathVariable("id") Long id, @AuthenticationPrincipal AuthUser user) {
        return eventService.seatMap(id, user == null ? null : user.id());
    }

    @PostMapping("/{id}/holds")
    public HoldResponse hold(@PathVariable("id") Long id, @Valid @RequestBody SeatSelection body,
                             @AuthenticationPrincipal AuthUser user) {
        eventService.require(id);
        HoldResult result = holdService.hold(id, body.seatIds(), user.id());
        broadcaster.seatsChanged(id, result.seatIds());
        return new HoldResponse(result.seatIds(), result.expiresAt());
    }

    @PostMapping("/{id}/holds/release")
    public ReleaseResponse release(@PathVariable("id") Long id, @Valid @RequestBody SeatSelection body,
                                   @AuthenticationPrincipal AuthUser user) {
        int released = holdService.release(id, body.seatIds(), user.id());
        broadcaster.seatsChanged(id, holdService.normalize(body.seatIds()));
        return new ReleaseResponse(released);
    }
}
