package com.seatlock.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Plain id columns instead of @ManyToOne keep queries explicit and avoid lazy-loading surprises.
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "booking_id")
    private Long bookingId;

    protected Seat() {
        // for JPA
    }

    public Seat(Long eventId, String rowLabel, int seatNumber, int priceCents) {
        this.eventId = eventId;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.priceCents = priceCents;
        this.status = SeatStatus.AVAILABLE;
    }

    /** Only legal on a row the caller has locked and verified as AVAILABLE. */
    public void markBooked(Long bookingId) {
        if (status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("Seat " + id + " is already booked");
        }
        this.status = SeatStatus.BOOKED;
        this.bookingId = bookingId;
    }

    public String label() {
        return rowLabel + seatNumber;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getRowLabel() { return rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public int getPriceCents() { return priceCents; }
    public SeatStatus getStatus() { return status; }
    public Long getBookingId() { return bookingId; }
}
