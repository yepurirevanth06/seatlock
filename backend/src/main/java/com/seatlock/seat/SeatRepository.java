package com.seatlock.seat;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderByRowLabelAscSeatNumberAsc(Long eventId);

    List<Seat> findByEventIdAndIdIn(Long eventId, Collection<Long> ids);

    List<Seat> findByBookingIdInOrderByRowLabelAscSeatNumberAsc(Collection<Long> bookingIds);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /**
     * The core of double-booking protection.
     *
     * FOR UPDATE takes a row lock on every requested seat until the transaction ends.
     * A second transaction asking for any of the same seats waits here, and when it
     * wakes up Postgres hands it the committed row, so it sees status = BOOKED.
     *
     * ORDER BY id makes every transaction lock seats in the same order. Without it,
     * a booking for seats [5, 7] and another for [7, 5] could each grab one lock and
     * wait forever on the other (a deadlock).
     */
    @Query(value = """
            SELECT * FROM seats
            WHERE event_id = :eventId AND id IN (:seatIds)
            ORDER BY id
            FOR UPDATE
            """, nativeQuery = true)
    List<Seat> lockForBooking(@Param("eventId") Long eventId, @Param("seatIds") Collection<Long> seatIds);

    @Query("""
            select s.eventId as eventId,
                   count(s) as total,
                   sum(case when s.status = com.seatlock.seat.SeatStatus.AVAILABLE then 1 else 0 end) as available
            from Seat s
            where s.eventId in :eventIds
            group by s.eventId
            """)
    List<SeatCounts> countsByEvent(@Param("eventIds") Collection<Long> eventIds);
}
