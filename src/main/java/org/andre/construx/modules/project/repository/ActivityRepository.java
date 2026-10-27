package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    /** Rows of [projectId, activityCount, doneCount]; projects without activities are absent. */
    @Query("select g.project.id, count(a), "
            + "sum(case when a.status = org.andre.construx.modules.project.model.ActivityStatus.DONE then 1 else 0 end) "
            + "from Activity a join a.goal g where g.project.id in :projectIds group by g.project.id")
    List<Object[]> countByProject(Collection<UUID> projectIds);
}
