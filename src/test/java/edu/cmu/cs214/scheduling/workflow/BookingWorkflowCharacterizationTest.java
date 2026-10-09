package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.MembershipTier;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins behavior of the shipped BookingWorkflow that the shipped tests leave open,
 * so a refactor that changes it goes red. Describes what the code does, not what
 * it should do.
 */
class BookingWorkflowCharacterizationTest {

    private static final LocalDateTime MON_9AM = LocalDateTime.of(2026, 10, 5, 9, 0);
    private static final LocalDateTime MON_10AM = LocalDateTime.of(2026, 10, 5, 10, 0);

    private BookingStore store;
    private NotificationHub hub;
    private BookingWorkflow workflow;

    @BeforeEach
    void setUp() {
        store = new BookingStore();
        store.addRoom(new Room("C-200", "Cedar Hall", 20));
        store.addMember(new Member("m-1", "Ada", "ada@rooms.example.edu", MembershipTier.BASIC));
        store.addMember(new Member("m-2", "Grace", "grace@rooms.example.edu",
                MembershipTier.PREMIER));
        hub = new NotificationHub();
        workflow = new BookingWorkflow(store, new PriceCalculator(), hub);
    }

    /*
     * The regular path treats touching slots as free (strict <), and
     * regularSubmitAcceptsASlotThatStartsWhenAnotherEnds pins that. The recurring
     * path compares with <=, so a week whose slot only touches an existing booking
     * is skipped, not booked.
     */
    @Test
    void recurringSubmitSkipsAWeekThatOnlyTouchesAnExistingBooking() {
        // Week 2 of the series: m-2 holds 08:00-09:00, ending exactly when the series starts.
        LocalDateTime week2_8am = LocalDateTime.of(2026, 10, 12, 8, 0);
        LocalDateTime week2_9am = LocalDateTime.of(2026, 10, 12, 9, 0);
        assertTrue(workflow.submit(
                BookingRequest.regular("C-200", "m-2", week2_8am, week2_9am, 4)).isAccepted());

        BookingOutcome outcome = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 3, 6));

        assertTrue(outcome.isAccepted());
        assertEquals(List.of(new TimeSlot(week2_9am, LocalDateTime.of(2026, 10, 12, 10, 0))),
                outcome.getSkipped());
        assertEquals(2, outcome.getBooked().size());
        assertEquals(1, outcome.getBooked().get(0).getOccurrenceIndex());
        assertEquals(3, outcome.getBooked().get(1).getOccurrenceIndex());
        assertEquals("series S-1: 2 booked, 1 skipped", outcome.getMessage());
        assertEquals(3, store.activeInRoom("C-200").size());
        assertEquals(3, hub.getOutbox().size());
    }
}
