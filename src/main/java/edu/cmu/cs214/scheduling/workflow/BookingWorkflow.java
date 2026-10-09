package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.notify.NotificationMessage;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The front door of the scheduler. Every booking that reaches the store goes
 * through here, and every notification the scheduler sends is published from
 * here.
 *
 * <p>What differs by booking type lives in one {@link TypeHandler} per
 * {@link BookingType}; each public method looks up the handler and delegates.
 */
public class BookingWorkflow {

    private static final String FACILITIES_CONTACT = "facilities@rooms.example.edu";
    private static final int MAX_SERIES_WEEKS = 26;

    private final BookingStore store;
    private final PriceCalculator calculator;
    private final NotificationHub hub;
    private final Map<BookingType, TypeHandler> handlers = new EnumMap<>(BookingType.class);

    public BookingWorkflow(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        if (store == null || calculator == null || hub == null) {
            throw new IllegalArgumentException("workflow collaborators must not be null");
        }
        this.store = store;
        this.calculator = calculator;
        this.hub = hub;
        handlers.put(BookingType.REGULAR, new RegularHandler());
        handlers.put(BookingType.RECURRING, new RecurringHandler());
        handlers.put(BookingType.BLOCKED, new BlockedHandler());
    }

    /**
     * Validates a request, writes what it can, and reports what it did.
     *
     * @return an outcome naming every booking written and every slot passed over
     */
    public BookingOutcome submit(BookingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Room room = store.findRoom(request.roomId());
        if (room == null) {
            return BookingOutcome.rejected("unknown room " + request.roomId());
        }
        return handlerFor(request.type()).submit(request, room);
    }

    /**
     * Releases a booking.
     *
     * @param adminOverride set by callers acting with facilities authority
     * @return true when something was released
     */
    public boolean cancel(long bookingId, boolean adminOverride) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null || booking.isCancelled()) {
            return false;
        }
        return handlerFor(booking.getType()).cancel(booking, roomNameOf(booking), adminOverride);
    }

    /** What the holder owes for a booking, in dollars. */
    public double priceOf(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("unknown booking " + bookingId);
        }
        return handlerFor(booking.getType()).price(booking);
    }

    /** A one-line summary for schedules and confirmation screens. */
    public String describe(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            return "Unknown booking #" + bookingId;
        }
        return handlerFor(booking.getType()).describe(booking, roomNameOf(booking));
    }

    private TypeHandler handlerFor(BookingType type) {
        return handlers.get(type);
    }

    private String roomNameOf(Booking booking) {
        Room room = store.findRoom(booking.getRoomId());
        return room == null ? booking.getRoomId() : room.getName();
    }

    private static String recipientFor(Member member) {
        return member == null ? FACILITIES_CONTACT : member.getEmail();
    }

    /** Half-open overlap: slots that only touch do not clash. */
    private static boolean overlaps(Booking existing, TimeSlot slot) {
        return existing.getStart().compareTo(slot.end()) < 0
                && slot.start().compareTo(existing.getEnd()) < 0;
    }

    /**
     * Closed overlap: slots that only touch DO clash. Only the recurring path
     * uses this, as the shipped code did; it is pinned by
     * BookingWorkflowCharacterizationTest.
     */
    private static boolean overlapsOrTouches(Booking existing, TimeSlot slot) {
        return existing.getStart().compareTo(slot.end()) <= 0
                && slot.start().compareTo(existing.getEnd()) <= 0;
    }

    /** Everything about a booking that depends on its type. */
    private abstract class TypeHandler {

        abstract BookingOutcome submit(BookingRequest request, Room room);

        abstract boolean cancel(Booking booking, String roomName, boolean adminOverride);

        abstract double price(Booking booking);

        abstract String describe(Booking booking, String roomName);
    }

    /** Shared by the two member-held types: checks the member and the room size. */
    private abstract class MemberHandler extends TypeHandler {

        /** @return a rejection, or null when the member exists and the party fits */
        BookingOutcome checkMemberAndCapacity(BookingRequest request, Member member, Room room) {
            if (member == null) {
                return BookingOutcome.rejected("unknown member " + request.memberId());
            }
            if (request.attendees() > room.getCapacity()) {
                return BookingOutcome.rejected("room " + room.getId() + " seats "
                        + room.getCapacity() + ", request wants " + request.attendees());
            }
            return null;
        }
    }

    private final class RegularHandler extends MemberHandler {

        @Override
        BookingOutcome submit(BookingRequest request, Room room) {
            Member member = store.findMember(request.memberId());
            BookingOutcome rejection = checkMemberAndCapacity(request, member, room);
            if (rejection != null) {
                return rejection;
            }

            TimeSlot slot = request.slot();
            for (Booking existing : store.activeInRoom(room.getId())) {
                if (overlaps(existing, slot)) {
                    return BookingOutcome.rejected("room " + room.getId()
                            + " is already booked at " + slot.start());
                }
            }

            for (Booking held : store.allBookings()) {
                if (held.isCancelled() || !member.getId().equals(held.getMemberId())) {
                    continue;
                }
                if (overlaps(held, slot)) {
                    return BookingOutcome.rejected(member.getId()
                            + " already holds a booking at " + slot.start());
                }
            }

            Booking booking = new Booking(store.nextBookingId(), room.getId(), member.getId(),
                    slot, BookingType.REGULAR, null, 0);
            store.save(booking);
            hub.publish(new NotificationMessage(member.getEmail(), "Booking confirmed",
                    "Room " + room.getName() + " from " + slot.start() + " to " + slot.end(),
                    slot.start()));
            return BookingOutcome.confirmed(booking,
                    "booked " + room.getId() + " for " + member.getId());
        }

        @Override
        boolean cancel(Booking booking, String roomName, boolean adminOverride) {
            Member member = store.findMember(booking.getMemberId());
            booking.cancel();
            hub.publish(new NotificationMessage(recipientFor(member), "Booking cancelled",
                    "Room " + roomName + " on " + booking.getStart() + " is free again",
                    booking.getStart()));
            return true;
        }

        @Override
        double price(Booking booking) {
            Member member = store.findMember(booking.getMemberId());
            return calculator.price(booking, member);
        }

        @Override
        String describe(Booking booking, String roomName) {
            return "Regular booking #" + booking.getId() + " in " + roomName
                    + " from " + booking.getStart() + " to " + booking.getEnd();
        }
    }

    private final class RecurringHandler extends MemberHandler {

        @Override
        BookingOutcome submit(BookingRequest request, Room room) {
            Member member = store.findMember(request.memberId());
            BookingOutcome rejection = checkMemberAndCapacity(request, member, room);
            if (rejection != null) {
                return rejection;
            }
            if (request.occurrences() < 1) {
                return BookingOutcome.rejected("a series needs at least one occurrence");
            }
            if (request.occurrences() > MAX_SERIES_WEEKS) {
                return BookingOutcome.rejected("a series runs at most "
                        + MAX_SERIES_WEEKS + " weeks");
            }

            String seriesId = store.nextSeriesId();
            List<Booking> booked = new ArrayList<>();
            List<TimeSlot> skipped = new ArrayList<>();

            for (int week = 0; week < request.occurrences(); week++) {
                TimeSlot slot = request.slot().plusWeeks(week);
                boolean taken = false;
                for (Booking existing : store.activeInRoom(room.getId())) {
                    if (overlapsOrTouches(existing, slot)) {
                        taken = true;
                        break;
                    }
                }
                if (taken) {
                    skipped.add(slot);
                    continue;
                }

                Booking occurrence = new Booking(store.nextBookingId(), room.getId(),
                        member.getId(), slot, BookingType.RECURRING, seriesId, week + 1);
                store.save(occurrence);
                booked.add(occurrence);
                hub.publish(new NotificationMessage(member.getEmail(), "Occurrence confirmed",
                        "Room " + room.getName() + " on " + slot.start().toLocalDate()
                                + " in series " + seriesId, slot.start()));
            }

            return BookingOutcome.series(booked, skipped, "series " + seriesId + ": "
                    + booked.size() + " booked, " + skipped.size() + " skipped");
        }

        @Override
        boolean cancel(Booking booking, String roomName, boolean adminOverride) {
            Member member = store.findMember(booking.getMemberId());
            for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
                if (occurrence.isCancelled()
                        || occurrence.getStart().compareTo(booking.getStart()) < 0) {
                    continue;
                }
                occurrence.cancel();
                hub.publish(new NotificationMessage(recipientFor(member),
                        "Occurrence cancelled",
                        "Room " + roomName + " on " + occurrence.getStart().toLocalDate()
                                + " in series " + occurrence.getSeriesId() + " is free again",
                        occurrence.getStart()));
            }
            return true;
        }

        @Override
        double price(Booking booking) {
            Member member = store.findMember(booking.getMemberId());
            double total = 0.0;
            for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
                if (occurrence.isCancelled()) {
                    continue;
                }
                total += calculator.price(occurrence, member);
            }
            return total;
        }

        @Override
        String describe(Booking booking, String roomName) {
            return "Recurring booking #" + booking.getId() + " in " + roomName
                    + ", occurrence " + booking.getOccurrenceIndex() + " of series "
                    + booking.getSeriesId() + ", " + booking.getStart()
                    + " to " + booking.getEnd();
        }
    }

    private final class BlockedHandler extends TypeHandler {

        @Override
        BookingOutcome submit(BookingRequest request, Room room) {
            TimeSlot slot = request.slot();
            if (!slot.start().toLocalDate().equals(slot.end().toLocalDate())) {
                return BookingOutcome.rejected("a block must stay inside one day");
            }

            for (Booking existing : store.activeInRoom(room.getId())) {
                if (overlaps(existing, slot)) {
                    return BookingOutcome.rejected("room " + room.getId()
                            + " cannot be blocked at " + slot.start());
                }
            }

            Booking block = new Booking(store.nextBookingId(), room.getId(), null, slot,
                    BookingType.BLOCKED, null, 0);
            store.save(block);
            hub.publish(new NotificationMessage(FACILITIES_CONTACT, "Room blocked",
                    "Room " + room.getName() + " held from " + slot.start()
                            + " to " + slot.end(), slot.start()));
            return BookingOutcome.confirmed(block, "blocked " + room.getId());
        }

        @Override
        boolean cancel(Booking booking, String roomName, boolean adminOverride) {
            if (!adminOverride) {
                return false;
            }
            booking.cancel();
            hub.publish(new NotificationMessage(FACILITIES_CONTACT, "Block released",
                    "Room " + roomName + " released from " + booking.getStart()
                            + " to " + booking.getEnd(), booking.getStart()));
            return true;
        }

        @Override
        double price(Booking booking) {
            return 0.0;
        }

        @Override
        String describe(Booking booking, String roomName) {
            return "Blocked slot #" + booking.getId() + " in " + roomName
                    + " from " + booking.getStart() + " to " + booking.getEnd()
                    + ", admin hold";
        }
    }
}
